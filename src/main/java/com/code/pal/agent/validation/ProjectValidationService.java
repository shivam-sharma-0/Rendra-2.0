package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executes only backend-selected package validation operations and returns bounded diagnostics. */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class ProjectValidationService {
    private static final Pattern TS_PAREN = Pattern.compile("^(.+?)\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON = Pattern.compile("^(.+?):(\\d+):(\\d+)\\s*(?:[-:]\\s*)?(error|warning)?\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final ChangeSetService changeSets;
    private final ProjectCommandResolver resolver;
    private final AgentProperties properties;
    private final SandboxPort sandbox;
    private final Map<UUID, ValidationReport> lastReports = new ConcurrentHashMap<>();

    /** Compatibility constructor for path-backed tests; production injection includes Docker. */
    public ProjectValidationService(ChangeSetService changeSets, ProjectCommandResolver resolver,
                                    AgentProperties properties) {
        this(changeSets, resolver, properties, new DockerProjectEnvironment(properties));
    }

    public Object diagnostics(AgentContext context) {
        var view = changeSets.view(context);
        var capabilities = context.sandboxed()
                ? resolver.inspectSandbox(sandbox, context.projectId(), ".")
                : resolver.inspect(view.liveRoot());
        var summary = new CapabilitySummary(capabilities.packageManager(), capabilities.declaredPackageManager(),
                capabilities.scripts().keySet().stream().sorted().toList(), capabilities.availableOperations(), capabilities.error());
        return new DiagnosticsSnapshot(summary, lastReports.get(context.runId()),
                changeSets.validationProgress(context));
    }

    public ValidationReport run(AgentContext context, ValidationOperation operation, String scope) {
        validateScope(scope);
        if (context.sandboxed()) return runSandbox(context, operation, scope);
        var view = changeSets.view(context);
        ProjectCommandResolver.CommandPlan plan = resolver.resolve(view.liveRoot(), operation, scope);
        if (!plan.available()) {
            ValidationReport report = reportUnavailable(context, plan);
            lastReports.put(context.runId(), report);
            return report;
        }
        Comparison comparison = compare(context, plan, scope);
        ValidationReport report = toReport(context, plan, comparison);
        if (!comparison.after().started() || comparison.after().timedOut()) {
            changeSets.recordTerminalValidationFailure(context, report.introducedDiagnostics());
        } else {
            changeSets.recordProjectValidation(context, report.changeValidationPassed(),
                    report.introducedDiagnostics(), false);
        }
        report = withProgress(report, changeSets.validationProgress(context));
        lastReports.put(context.runId(), report);
        return report;
    }

    /** Required final gate: structural checks plus every applicable type-check/build operation. */
    public ValidationReport validateAll(AgentContext context) {
        if (context.sandboxed()) return validateAllSandbox(context);
        ChangeSetService.ValidationResult structure = changeSets.validate(context);
        if (!structure.valid()) {
            List<ProjectDiagnostic> diagnostics = structure.issues().stream().map(issue ->
                    new ProjectDiagnostic(issue.path(), null, null, DiagnosticSeverity.ERROR,
                            issue.message(), "structure", issue.code())).toList();
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateReport(context, false, diagnostics, List.of(),
                    "Deterministic file validation failed");
            lastReports.put(context.runId(), report);
            return report;
        }

        var view = changeSets.view(context);
        if (resolver.validationScriptsChanged(view.liveRoot(), view.stagedRoot())) {
            List<ProjectDiagnostic> diagnostics = List.of(new ProjectDiagnostic("package.json", null, null,
                    DiagnosticSeverity.ERROR, "Validation scripts changed during the run and cannot be executed as trusted commands",
                    "validation-security", "VALIDATION_SCRIPT_CHANGED"));
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateReport(context, false, diagnostics, List.of(),
                    "Restore the original package scripts before validation");
            lastReports.put(context.runId(), report);
            return report;
        }
        List<Comparison> comparisons = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        for (ValidationOperation operation : List.of(ValidationOperation.TYPE_CHECK, ValidationOperation.BUILD)) {
            ProjectCommandResolver.CommandPlan plan = resolver.resolve(view.liveRoot(), operation, null);
            if (!plan.available()) continue;
            comparisons.add(compare(context, plan, null));
            executed.add(operation.name());
        }
        List<ProjectDiagnostic> introduced = comparisons.stream().flatMap(value -> value.introduced().stream()).toList();
        List<ProjectDiagnostic> existing = comparisons.stream().flatMap(value -> value.preExisting().stream()).toList();
        boolean passed = comparisons.stream().allMatch(Comparison::changePassed);
        boolean terminal = comparisons.stream().anyMatch(value -> !value.after().started() || value.after().timedOut());
        if (terminal) changeSets.recordTerminalValidationFailure(context, introduced);
        else changeSets.recordProjectValidation(context, passed, introduced);
        String summary = passed
                ? (executed.isEmpty() ? "Structural validation passed; no type-check/build script is configured"
                                      : "Validation passed: " + String.join(", ", executed))
                : "Validation found errors introduced by the staged changes";
        ValidationReport report = aggregateReport(context, passed, introduced, existing, summary);
        lastReports.put(context.runId(), report);
        return report;
    }

    public ValidationReport lastReport(UUID runId) { return lastReports.get(runId); }
    public void clear(UUID runId) { lastReports.remove(runId); }

    private ValidationReport runSandbox(AgentContext context, ValidationOperation operation, String scope) {
        ProjectCommandResolver.CommandPlan plan = resolver.resolveSandbox(sandbox, context.projectId(), ".", operation, scope);
        if (!plan.available()) {
            ValidationReport report = reportUnavailable(context, plan);
            lastReports.put(context.runId(), report);
            return report;
        }
        Comparison comparison = compareSandbox(context, plan, scope);
        ValidationReport report = toReport(context, plan, comparison);
        if (!comparison.after().started() || comparison.after().timedOut()) {
            changeSets.recordTerminalValidationFailure(context, report.introducedDiagnostics());
        } else {
            changeSets.recordProjectValidation(context, report.changeValidationPassed(),
                    report.introducedDiagnostics(), false);
        }
        report = withProgress(report, changeSets.validationProgress(context));
        lastReports.put(context.runId(), report);
        return report;
    }

    private ValidationReport validateAllSandbox(AgentContext context) {
        ChangeSetService.ValidationResult structure = changeSets.validate(context);
        if (!structure.valid()) {
            List<ProjectDiagnostic> diagnostics = structure.issues().stream().map(issue ->
                    new ProjectDiagnostic(issue.path(), null, null, DiagnosticSeverity.ERROR,
                            issue.message(), "structure", issue.code())).toList();
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateSandboxReport(context, false, diagnostics, List.of(),
                    "Deterministic file validation failed");
            lastReports.put(context.runId(), report);
            return report;
        }
        if (resolver.validationScriptsChanged(sandbox, context.projectId(), ".", context.sandboxRoot())) {
            List<ProjectDiagnostic> diagnostics = List.of(new ProjectDiagnostic("package.json", null, null,
                    DiagnosticSeverity.ERROR, "Validation scripts changed during the run and cannot be executed as trusted commands",
                    "validation-security", "VALIDATION_SCRIPT_CHANGED"));
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateSandboxReport(context, false, diagnostics, List.of(),
                    "Restore the original package scripts before validation");
            lastReports.put(context.runId(), report);
            return report;
        }
        List<Comparison> comparisons = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        for (ValidationOperation operation : List.of(ValidationOperation.TYPE_CHECK, ValidationOperation.BUILD)) {
            ProjectCommandResolver.CommandPlan plan = resolver.resolveSandbox(sandbox, context.projectId(), ".", operation, null);
            if (!plan.available()) continue;
            comparisons.add(compareSandbox(context, plan, null));
            executed.add(operation.name());
        }
        List<ProjectDiagnostic> introduced = comparisons.stream().flatMap(value -> value.introduced().stream()).toList();
        List<ProjectDiagnostic> existing = comparisons.stream().flatMap(value -> value.preExisting().stream()).toList();
        boolean passed = comparisons.stream().allMatch(Comparison::changePassed);
        boolean terminal = comparisons.stream().anyMatch(value -> !value.after().started() || value.after().timedOut());
        if (terminal) changeSets.recordTerminalValidationFailure(context, introduced);
        else changeSets.recordProjectValidation(context, passed, introduced);
        String summary = passed
                ? (executed.isEmpty() ? "Structural validation passed; no type-check/build script is configured"
                                      : "Validation passed: " + String.join(", ", executed))
                : "Validation found errors introduced by the staged changes";
        ValidationReport report = aggregateSandboxReport(context, passed, introduced, existing, summary);
        lastReports.put(context.runId(), report);
        return report;
    }

    private Comparison compareSandbox(AgentContext context, ProjectCommandResolver.CommandPlan plan, String scope) {
        Path baselinePath = changeSets.createValidationBaseline(context, plan.operation().name().toLowerCase(Locale.ROOT));
        Path candidatePath = changeSets.createValidationCandidate(context, plan.operation().name().toLowerCase(Locale.ROOT));
        String baseline = sandboxRoot(baselinePath);
        String candidate = sandboxRoot(candidatePath);
        try {
            resolver.preserveOriginalScripts(sandbox, context.projectId(), ".", candidate);
            CommandOutcome before = executeSandbox(context, plan, baseline);
            CommandOutcome after = executeSandbox(context, plan, candidate);
            List<ProjectDiagnostic> baselineDiagnostics = diagnostics(before, baselinePath, plan.operation(), changeSets.view(context).touchedPaths());
            List<ProjectDiagnostic> currentDiagnostics = diagnostics(after, candidatePath, plan.operation(), changeSets.view(context).touchedPaths());
            Set<String> baselineKeys = baselineDiagnostics.stream().map(ProjectValidationService::responsibilityFingerprint)
                    .collect(java.util.stream.Collectors.toSet());
            List<ProjectDiagnostic> introduced = currentDiagnostics.stream()
                    .filter(item -> !baselineKeys.contains(responsibilityFingerprint(item))).toList();
            List<ProjectDiagnostic> existing = currentDiagnostics.stream()
                    .filter(item -> baselineKeys.contains(responsibilityFingerprint(item))).toList();
            boolean passed;
            if (!after.started() || after.timedOut()) passed = false;
            else if (after.exitCode() == 0) passed = true;
            else passed = before.started() && !before.timedOut() && before.exitCode() != 0 && introduced.isEmpty();
            if (!passed && introduced.isEmpty()) introduced = List.of(infrastructureDiagnostic(after, plan.operation()));
            var touched = changeSets.view(context).touchedPaths();
            int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - introduced.size());
            return new Comparison(plan, before, after, bounded(introduced, touched), bounded(existing, touched, remaining), passed);
        } finally {
            try { sandbox.removeTree(context.projectId(), baseline); } catch (RuntimeException ignored) { }
            try { sandbox.removeTree(context.projectId(), candidate); } catch (RuntimeException ignored) { }
        }
    }

    private CommandOutcome executeSandbox(AgentContext context, ProjectCommandResolver.CommandPlan plan, String root) {
        var result = sandbox.exec(context.projectId(), root, plan.command());
        String output = result.stdout() + (result.error().isBlank() ? "" : "\n" + result.error());
        return new CommandOutcome(!result.error().startsWith("Docker command could not be started"),
                result.error().toLowerCase(Locale.ROOT).contains("timed out"), result.exitCode(), output);
    }

    private ValidationReport aggregateSandboxReport(AgentContext context, boolean passed,
                                                     List<ProjectDiagnostic> introduced,
                                                     List<ProjectDiagnostic> existing, String summary) {
        var progress = changeSets.validationProgress(context);
        var capabilities = resolver.inspectSandbox(sandbox, context.projectId(), ".");
        var view = changeSets.view(context);
        List<ProjectDiagnostic> boundedIntroduced = bounded(introduced, view.touchedPaths());
        int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - boundedIntroduced.size());
        return new ValidationReport(ValidationOperation.ALL, true, passed, passed, false, passed ? 0 : 1,
                capabilities.packageManager(), "project validation", boundedIntroduced,
                bounded(existing, view.touchedPaths(), remaining), progress.repairAttempts(),
                progress.repairAttemptsRemaining(), progress.revision(), summary);
    }

    private static String sandboxRoot(Path path) {
        String value = path.toString().replace('\\', '/');
        if (value.startsWith("/workspace/")) return value.substring("/workspace/".length());
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private Comparison compare(AgentContext context, ProjectCommandResolver.CommandPlan plan, String scope) {
        var view = changeSets.view(context);
        Path baseline = changeSets.createValidationBaseline(context, plan.operation().name().toLowerCase(Locale.ROOT));
        Path candidate = changeSets.createValidationCandidate(context, plan.operation().name().toLowerCase(Locale.ROOT));
        resolver.preserveOriginalScripts(view.liveRoot(), candidate);
        attachDependencyTree(view.liveRoot(), baseline);
        attachDependencyTree(view.liveRoot(), candidate);
        CommandOutcome before = execute(plan, baseline, view.liveRoot());
        CommandOutcome after = execute(plan, candidate, view.liveRoot());

        List<ProjectDiagnostic> baselineDiagnostics = diagnostics(before, baseline, plan.operation(), view.touchedPaths());
        List<ProjectDiagnostic> currentDiagnostics = diagnostics(after, candidate, plan.operation(), view.touchedPaths());
        Set<String> baselineKeys = baselineDiagnostics.stream().map(ProjectValidationService::responsibilityFingerprint)
                .collect(java.util.stream.Collectors.toSet());
        List<ProjectDiagnostic> introduced = currentDiagnostics.stream()
                .filter(item -> !baselineKeys.contains(responsibilityFingerprint(item))).toList();
        List<ProjectDiagnostic> existing = currentDiagnostics.stream()
                .filter(item -> baselineKeys.contains(responsibilityFingerprint(item))).toList();

        boolean passed;
        if (!after.started() || after.timedOut()) passed = false;
        else if (after.exitCode() == 0) passed = true;
        else passed = before.started() && !before.timedOut() && before.exitCode() != 0 && introduced.isEmpty();

        if (!passed && introduced.isEmpty()) {
            ProjectDiagnostic diagnostic = infrastructureDiagnostic(after, plan.operation());
            introduced = List.of(diagnostic);
        }
        List<ProjectDiagnostic> boundedIntroduced = bounded(introduced, view.touchedPaths());
        int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - boundedIntroduced.size());
        return new Comparison(plan, before, after, boundedIntroduced,
                bounded(existing, view.touchedPaths(), remaining), passed);
    }

    private CommandOutcome execute(ProjectCommandResolver.CommandPlan plan, Path workingDirectory, Path liveRoot) {
        ProcessBuilder builder = new ProcessBuilder(plan.command()).directory(workingDirectory.toFile()).redirectErrorStream(true);
        sanitizeEnvironment(builder.environment(), liveRoot);
        Process process;
        try { process = builder.start(); }
        catch (IOException error) { return new CommandOutcome(false, false, -1, "Validation executable could not be started"); }

        OutputCapture capture = new OutputCapture(process.getInputStream(), Math.max(1_000, properties.getValidationOutputMaxChars()));
        Thread drain = new Thread(capture, "agent-validation-output");
        drain.setDaemon(true);
        drain.start();
        boolean finished;
        try {
            finished = process.waitFor(Math.max(1_000, properties.getValidationTimeoutMs()), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            drain.join(1_000);
            return new CommandOutcome(true, !finished, finished ? process.exitValue() : -1, capture.text());
        } catch (InterruptedException error) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AgentToolException("VALIDATION_INTERRUPTED", "Project validation was interrupted");
        }
    }

    private void sanitizeEnvironment(Map<String, String> environment, Path liveRoot) {
        Map<String, String> inherited = new HashMap<>(environment);
        environment.clear();
        for (String key : List.of("SystemRoot", "ComSpec", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA")) {
            if (inherited.get(key) != null) environment.put(key, inherited.get(key));
        }
        String originalPath = inherited.getOrDefault("PATH", inherited.getOrDefault("Path", ""));
        String binaryPath = liveRoot.resolve("node_modules/.bin").toAbsolutePath().toString();
        environment.put("PATH", binaryPath + java.io.File.pathSeparator + originalPath);
        environment.put("NODE_PATH", liveRoot.resolve("node_modules").toAbsolutePath().toString());
        environment.put("CI", "true");
        environment.put("NO_COLOR", "1");
    }

    private static void attachDependencyTree(Path liveRoot, Path validationRoot) {
        Path dependencies = liveRoot.resolve("node_modules").toAbsolutePath().normalize();
        Path link = validationRoot.resolve("node_modules");
        if (!Files.isDirectory(dependencies) || Files.exists(link)) return;
        try {
            Files.createSymbolicLink(link, dependencies);
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // PATH/NODE_PATH still cover script executables and Node resolution. Some Windows hosts
            // disallow symlinks; baseline comparison prevents those environment errors being blamed
            // on the staged change.
        }
    }

    private List<ProjectDiagnostic> diagnostics(CommandOutcome outcome, Path root, ValidationOperation operation,
                                                List<String> touched) {
        List<ProjectDiagnostic> diagnostics = new ArrayList<>();
        for (String raw : outcome.output().split("\\R")) {
            String line = ANSI.matcher(raw).replaceAll("").trim();
            if (line.isEmpty()) continue;
            Matcher matcher = TS_PAREN.matcher(line);
            if (matcher.matches()) {
                diagnostics.add(parsed(root, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                        matcher.group(6), operation, matcher.group(5)));
                continue;
            }
            matcher = COLON.matcher(line);
            if (matcher.matches()) {
                diagnostics.add(parsed(root, matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4),
                        matcher.group(6), operation, matcher.group(5)));
            }
        }
        if (outcome.exitCode() != 0 && diagnostics.isEmpty() && !outcome.output().isBlank()) {
            String useful = Arrays.stream(outcome.output().split("\\R")).map(String::trim)
                    .filter(line -> line.toLowerCase(Locale.ROOT).contains("error") || line.toLowerCase(Locale.ROOT).contains("failed"))
                    .findFirst().orElse("Validation command failed");
            diagnostics.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                    truncate(normalizeMessage(root, useful), 400), operation.name().toLowerCase(Locale.ROOT), null));
        }
        return bounded(diagnostics, touched);
    }

    private ProjectDiagnostic parsed(Path root, String file, String line, String column, String severity,
                                     String message, ValidationOperation operation, String code) {
        String normalized = normalizeFile(root, file);
        DiagnosticSeverity level = "warning".equalsIgnoreCase(severity) ? DiagnosticSeverity.WARNING : DiagnosticSeverity.ERROR;
        return new ProjectDiagnostic(normalized, Integer.valueOf(line), Integer.valueOf(column), level,
                truncate(normalizeMessage(root, message.trim()), 400), operation.name().toLowerCase(Locale.ROOT), code);
    }

    private List<ProjectDiagnostic> bounded(List<ProjectDiagnostic> input, List<String> touched) {
        return bounded(input, touched, Math.max(1, properties.getMaxValidationDiagnostics()));
    }

    private List<ProjectDiagnostic> bounded(List<ProjectDiagnostic> input, List<String> touched, int limit) {
        if (limit <= 0) return List.of();
        Set<String> changed = new HashSet<>(touched);
        return input.stream().distinct().sorted(Comparator
                        .comparing((ProjectDiagnostic item) -> item.severity() == DiagnosticSeverity.ERROR ? 0 : 1)
                        .thenComparing(item -> item.file() != null && changed.contains(item.file()) ? 0 : 1)
                        .thenComparing(item -> item.file() == null ? "" : item.file())
                        .thenComparing(item -> item.line() == null ? 0 : item.line()))
                .limit(limit).toList();
    }

    private ValidationReport toReport(AgentContext context, ProjectCommandResolver.CommandPlan plan, Comparison comparison) {
        var progress = changeSets.validationProgress(context);
        return new ValidationReport(plan.operation(), true, comparison.after().exitCode() == 0,
                comparison.changePassed(), comparison.after().timedOut(), comparison.after().exitCode(),
                plan.packageManager(), plan.script(), comparison.introduced(), comparison.preExisting(),
                progress.repairAttempts(), progress.repairAttemptsRemaining(), progress.revision(),
                comparison.changePassed() ? "No new diagnostics were introduced" : "Staged changes introduced validation errors");
    }

    private ValidationReport withProgress(ValidationReport report, ChangeSetService.ValidationProgress progress) {
        return new ValidationReport(report.operation(), report.available(), report.commandSucceeded(), report.changeValidationPassed(),
                report.timedOut(), report.exitCode(), report.packageManager(), report.script(), report.introducedDiagnostics(),
                report.preExistingDiagnostics(), progress.repairAttempts(), progress.repairAttemptsRemaining(),
                progress.revision(), report.summary());
    }

    private ValidationReport aggregateReport(AgentContext context, boolean passed, List<ProjectDiagnostic> introduced,
                                             List<ProjectDiagnostic> existing, String summary) {
        var progress = changeSets.validationProgress(context);
        var view = changeSets.view(context);
        List<ProjectDiagnostic> boundedIntroduced = bounded(introduced, view.touchedPaths());
        int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - boundedIntroduced.size());
        return new ValidationReport(ValidationOperation.ALL, true, passed, passed, false, passed ? 0 : 1,
                resolver.inspect(view.liveRoot()).packageManager(), "project validation", boundedIntroduced,
                bounded(existing, view.touchedPaths(), remaining), progress.repairAttempts(),
                progress.repairAttemptsRemaining(), progress.revision(), summary);
    }

    private ValidationReport reportUnavailable(AgentContext context, ProjectCommandResolver.CommandPlan plan) {
        var progress = changeSets.validationProgress(context);
        return new ValidationReport(plan.operation(), false, false, false, false, null, plan.packageManager(), null,
                List.of(), List.of(), progress.repairAttempts(), progress.repairAttemptsRemaining(), progress.revision(),
                plan.unavailableReason());
    }

    private static ProjectDiagnostic infrastructureDiagnostic(CommandOutcome outcome, ValidationOperation operation) {
        String message = outcome.timedOut() ? "Validation timed out" : (!outcome.started()
                ? "Validation executable could not be started" : "Validation failed without a parseable diagnostic");
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, message,
                operation.name().toLowerCase(Locale.ROOT), null);
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank()) return;
        if (scope.length() > 200 || scope.startsWith("-") || scope.indexOf('\0') >= 0 || Path.of(scope).isAbsolute()) {
            throw new AgentToolException("INVALID_TEST_SCOPE", "Test scope must be a short relative path or test name");
        }
        for (Path part : Path.of(scope)) if (part.toString().equals("..")) {
            throw new AgentToolException("INVALID_TEST_SCOPE", "Parent path traversal is not allowed in test scope");
        }
        if (!scope.matches("[A-Za-z0-9_./\\\\:-]+")) {
            throw new AgentToolException("INVALID_TEST_SCOPE", "Test scope contains unsupported characters");
        }
    }

    private static String normalizeFile(Path root, String raw) {
        String clean = raw.trim().replace('\\', '/');
        try {
            Path path = Path.of(raw.trim()).toAbsolutePath().normalize();
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (path.startsWith(normalizedRoot)) return normalizedRoot.relativize(path).toString().replace('\\', '/');
        } catch (RuntimeException ignored) { }
        while (clean.startsWith("./")) clean = clean.substring(2);
        return clean;
    }
    private static String normalizeMessage(Path root, String message) {
        String normalized = message.replace(root.toAbsolutePath().normalize().toString(), "<project>");
        return normalized.replace(root.toAbsolutePath().normalize().toString().replace('\\', '/'), "<project>");
    }
    private static String responsibilityFingerprint(ProjectDiagnostic item) {
        return String.join("|", item.file() == null ? "" : item.file(), item.severity().name(),
                item.message(), item.source(), item.code() == null ? "" : item.code());
    }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "..."; }

    private record CommandOutcome(boolean started, boolean timedOut, int exitCode, String output) {}
    private record Comparison(ProjectCommandResolver.CommandPlan plan, CommandOutcome before, CommandOutcome after,
                              List<ProjectDiagnostic> introduced, List<ProjectDiagnostic> preExisting,
                              boolean changePassed) {}
    public record CapabilitySummary(String packageManager, String declaredPackageManager, List<String> scriptNames,
                                    List<String> availableOperations, String error) {}
    public record DiagnosticsSnapshot(CapabilitySummary capabilities, ValidationReport lastReport,
                                      ChangeSetService.ValidationProgress progress) {}

    private static final class OutputCapture implements Runnable {
        private final InputStream input; private final int limit; private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private OutputCapture(InputStream input, int limit) { this.input = input; this.limit = limit; }
        @Override public void run() {
            try (InputStream stream = input) {
                byte[] buffer = new byte[8192];
                for (int count; (count = stream.read(buffer)) >= 0;) {
                    int remaining = limit - kept.size();
                    if (remaining > 0) kept.write(buffer, 0, Math.min(count, remaining));
                }
            } catch (IOException ignored) { }
        }
        private String text() { return kept.toString(StandardCharsets.UTF_8); }
    }
}
