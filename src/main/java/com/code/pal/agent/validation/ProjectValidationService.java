package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.stack.CommandPlan;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes only backend-selected package validation operations in the sandbox and returns bounded
 * diagnostics. Each operation runs twice, on a copy of the live project and on a copy of the staged
 * change set, so only diagnostics the change introduced are blamed on it.
 */
@Service
@RequiredArgsConstructor
public class ProjectValidationService {
    private static final Pattern TS_PAREN = Pattern.compile("^(.+?)\\((\\d+),(\\d+)\\):\\s*(error|warning)\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLON = Pattern.compile("^(.+?):(\\d+):(\\d+)\\s*(?:[-:]\\s*)?(error|warning)?\\s*([A-Za-z]+\\d+)?:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");

    private final ChangeSetService changeSets;
    private final StackPacks stacks;
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final Map<UUID, ValidationReport> lastReports = new ConcurrentHashMap<>();

    public Object diagnostics(AgentContext context) {
        var capabilities = stack(context).inspect(files, context.projectId(), ".");
        var summary = new CapabilitySummary(capabilities.stack(), capabilities.packageManager(), capabilities.declaredPackageManager(),
                capabilities.scripts().keySet().stream().sorted().toList(), capabilities.availableOperations(), capabilities.error());
        return new DiagnosticsSnapshot(summary, lastReports.get(context.runId()),
                changeSets.validationProgress(context));
    }

    public ValidationReport run(AgentContext context, ValidationOperation operation, String scope) {
        validateScope(scope);
        CommandPlan plan = stack(context).resolve(files, context.projectId(), ".", operation, scope);
        if (!plan.available()) {
            ValidationReport report = reportUnavailable(context, plan);
            lastReports.put(context.runId(), report);
            return report;
        }
        Comparison comparison = compare(context, plan);
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
        StackPack stack = stack(context);
        if (stack.trustedCommandsChanged(files, context.projectId(), view.liveRoot(), view.stagedRoot())) {
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
            CommandPlan plan = stack.resolve(files, context.projectId(), view.liveRoot(), operation, null);
            if (!plan.available()) continue;
            comparisons.add(compare(context, plan));
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

    /** The stack of the live project: commands come from its original, trusted manifests. */
    private StackPack stack(AgentContext context) {
        return stacks.detect(files, context.projectId(), ".");
    }

    public ValidationReport lastReport(UUID runId) { return lastReports.get(runId); }
    public void clear(UUID runId) { lastReports.remove(runId); }

    private Comparison compare(AgentContext context, CommandPlan plan) {
        String name = plan.operation().name().toLowerCase(Locale.ROOT);
        String baseline = changeSets.createValidationBaseline(context, name);
        try {
            String candidate = changeSets.createValidationCandidate(context, name);
            try {
                stack(context).preserveTrustedCommands(files, context.projectId(), ".", candidate);
                CommandOutcome before = execute(context, plan, baseline);
                CommandOutcome after = execute(context, plan, candidate);
                List<String> touched = changeSets.view(context).touchedPaths();
                List<ProjectDiagnostic> baselineDiagnostics = diagnostics(before, plan.operation(), touched);
                List<ProjectDiagnostic> currentDiagnostics = diagnostics(after, plan.operation(), touched);
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
                List<ProjectDiagnostic> boundedIntroduced = bounded(introduced, touched);
                int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - boundedIntroduced.size());
                return new Comparison(plan, before, after, boundedIntroduced, bounded(existing, touched, remaining), passed);
            } finally {
                try { sandbox.removeTree(context.projectId(), candidate); } catch (RuntimeException ignored) { }
            }
        } finally {
            try { sandbox.removeTree(context.projectId(), baseline); } catch (RuntimeException ignored) { }
        }
    }

    private CommandOutcome execute(AgentContext context, CommandPlan plan, String root) {
        var result = sandbox.exec(context.projectId(), root, plan.command());
        String output = result.stdout() + (result.error().isBlank() ? "" : "\n" + result.error());
        return new CommandOutcome(result.started(), result.timedOut(), result.exitCode(), output,
                sandbox.commandPath(context.projectId(), root));
    }

    private List<ProjectDiagnostic> diagnostics(CommandOutcome outcome, ValidationOperation operation, List<String> touched) {
        String root = outcome.root();
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

    private ProjectDiagnostic parsed(String root, String file, String line, String column, String severity,
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

    private ValidationReport toReport(AgentContext context, CommandPlan plan, Comparison comparison) {
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
                stack(context).inspect(files, context.projectId(), view.liveRoot()).packageManager(), "project validation",
                boundedIntroduced, bounded(existing, view.touchedPaths(), remaining), progress.repairAttempts(),
                progress.repairAttemptsRemaining(), progress.revision(), summary);
    }

    private ValidationReport reportUnavailable(AgentContext context, CommandPlan plan) {
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

    /** A tool's file reference relative to the directory the command ran in ({@code root}, as the command saw it). */
    static String normalizeFile(String root, String raw) {
        String clean = raw.trim().replace('\\', '/');
        String base = root.replace('\\', '/').replaceAll("/+$", "");
        if (!base.isEmpty() && clean.regionMatches(true, 0, base + "/", 0, base.length() + 1)) {
            clean = clean.substring(base.length() + 1);
        }
        while (clean.startsWith("./")) clean = clean.substring(2);
        return clean;
    }

    private static String normalizeMessage(String root, String message) {
        String forward = root.replace('\\', '/');
        return message.replace(root, "<project>").replace(forward, "<project>");
    }
    private static String responsibilityFingerprint(ProjectDiagnostic item) {
        return String.join("|", item.file() == null ? "" : item.file(), item.severity().name(),
                item.message(), item.source(), item.code() == null ? "" : item.code());
    }
    private static String truncate(String value, int max) { return value.length() <= max ? value : value.substring(0, max) + "..."; }

    /** {@code root}: the directory the command ran in, as the command saw it (for mapping its paths back). */
    private record CommandOutcome(boolean started, boolean timedOut, int exitCode, String output, String root) {}
    private record Comparison(CommandPlan plan, CommandOutcome before, CommandOutcome after,
                              List<ProjectDiagnostic> introduced, List<ProjectDiagnostic> preExisting,
                              boolean changePassed) {}
    public record CapabilitySummary(String stack, String packageManager, String declaredPackageManager, List<String> scriptNames,
                                    List<String> availableOperations, String error) {}
    public record DiagnosticsSnapshot(CapabilitySummary capabilities, ValidationReport lastReport,
                                      ChangeSetService.ValidationProgress progress) {}
}
