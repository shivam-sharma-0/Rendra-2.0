package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.stack.CommandPlan;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.code.pal.project.capability.BuildDecisions;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Executes only backend-selected package validation operations in the sandbox and returns bounded
 * diagnostics. Each operation runs twice, on a copy of the live project and on a copy of the staged
 * change set, so only diagnostics the change introduced are blamed on it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectValidationService {

    private final ChangeSetService changeSets;
    private final StackPacks stacks;
    private final AgentProperties properties;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final DependencyService dependencies;
    /** Browser check of the staged site; null where no browser is wired (focused tests). */
    private final RuntimeCheckService runtime;
    private final Map<UUID, ValidationReport> lastReports = new ConcurrentHashMap<>();
    /**
     * Comparisons already run for a staged revision, per run. The live project is locked for the run and
     * the candidate is the staged revision, so an operation's result cannot change until the agent writes
     * again; the final gate reuses what the agent's own typeCheck/build calls already ran.
     */
    private final Map<UUID, RevisionResults> results = new ConcurrentHashMap<>();
    private BuildDecisions buildDecisions;
    private MernBehaviorCheck mernBehavior;
    private RunBehaviorCriteria behaviorCriteria;
    /** Production checklist; null in focused tests that construct the service directly. */
    private ProductionBaseline baseline;

    @Autowired
    public void useProductionBaseline(ProductionBaseline baseline) {
        this.baseline = Objects.requireNonNull(baseline);
    }

    @Autowired
    public void useBehaviorCriteria(RunBehaviorCriteria criteria) {
        this.behaviorCriteria = Objects.requireNonNull(criteria);
    }

    @Autowired
    public void useMernBehavior(MernBehaviorCheck check) {
        this.mernBehavior = Objects.requireNonNull(check);
    }

    @Autowired
    public void setBuildDecisions(BuildDecisions decisions) {
        this.buildDecisions = Objects.requireNonNull(decisions);
    }

    public Object diagnostics(AgentContext context) {
        var capabilities = stack(context).inspect(files, context.projectId(), ".");
        var summary = new CapabilitySummary(capabilities.stack(), capabilities.packageManager(),
                capabilities.declaredPackageManager(),
                capabilities.scripts().keySet().stream().sorted().toList(), capabilities.availableOperations(),
                capabilities.error());
        return new DiagnosticsSnapshot(summary, lastReports.get(context.runId()),
                changeSets.validationProgress(context));
    }

    /** The trusted command an operation would run on the staged change, or null when none is available. */
    public List<String> plannedCommand(AgentContext context, ValidationOperation operation) {
        CommandPlan plan = plan(context, operation, null);
        return plan.available() ? List.copyOf(plan.command()) : null;
    }

    public ValidationReport run(AgentContext context, ValidationOperation operation, String scope) {
        validateScope(scope);
        CommandPlan plan = plan(context, operation, scope);
        if (!plan.available()) {
            ValidationReport report = reportUnavailable(context, plan);
            lastReports.put(context.runId(), report);
            return report;
        }
        Comparison comparison = scope == null || scope.isBlank() ? compareOnce(context, plan) : compare(context, plan);
        ValidationReport report = toReport(context, plan, comparison);
        if (!comparison.after().started() || comparison.after().timedOut()) {
            changeSets.recordTerminalValidationFailure(context, report.introducedDiagnostics());
        } else {
            changeSets.recordProjectValidation(context, report.changeValidationPassed(),
                    report.introducedDiagnostics(), false);
        }
        report = withProgress(report, changeSets.validationProgress(context));
        if (!report.changeValidationPassed())
            logDiagnostics(context, operation, report.introducedDiagnostics());
        lastReports.put(context.runId(), report);
        return report;
    }

    /** Required final gate: structural checks plus every applicable type-check/build operation. */
    public ValidationReport validateAll(AgentContext context) {
        return validateAll(context, false);
    }

    /**
     * Final gate when bound compiler receipts already prove TYPE_CHECK/BUILD for this exact staged identity:
     * structure, runtime/browser acceptance and the production baseline run without re-entering the compilers.
     */
    public ValidationReport validateFinal(AgentContext context) {
        return validateAll(context, true);
    }

    /** Drops a run's cached command and browser results, for a compilation identity that changed in place. */
    public void forgetResults(UUID runId) {
        results.remove(runId);
    }

    private ValidationReport validateAll(AgentContext context, boolean compilersProven) {
        String capabilityProblem = buildDecisions == null ? null
                : buildDecisions.validate(context.projectId(), files, context.root());
        if (capabilityProblem != null) {
            List<ProjectDiagnostic> diagnostics = List.of(new ProjectDiagnostic("", null, null,
                    DiagnosticSeverity.ERROR, capabilityProblem, "capabilities", "BUILD_DECISION_MISMATCH"));
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateReport(context, false, diagnostics, List.of(), capabilityProblem);
            lastReports.put(context.runId(), report);
            return report;
        }
        ChangeSetService.ValidationResult structure = changeSets.validate(context);
        if (!structure.valid()) {
            List<ProjectDiagnostic> diagnostics = structure.issues().stream()
                    .map(issue -> new ProjectDiagnostic(issue.path(), null, null, DiagnosticSeverity.ERROR,
                            issue.message(), "structure", issue.code()))
                    .toList();
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
                    DiagnosticSeverity.ERROR,
                    "Validation scripts changed during the run and cannot be executed as trusted commands",
                    "validation-security", "VALIDATION_SCRIPT_CHANGED"));
            changeSets.recordProjectValidation(context, false, diagnostics);
            ValidationReport report = aggregateReport(context, false, diagnostics, List.of(),
                    "Restore the original package scripts before validation");
            lastReports.put(context.runId(), report);
            return report;
        }
        List<Comparison> comparisons = new ArrayList<>();
        List<ValidationCheck> checks = new ArrayList<>();
        checks.add(new ValidationCheck("STRUCTURE", ValidationCheck.Outcome.PASS, true,
                "Deterministic staged file validation passed"));
        List<String> executed = new ArrayList<>();
        for (ValidationOperation operation : List.of(ValidationOperation.TYPE_CHECK, ValidationOperation.BUILD)) {
            if (compilersProven) {
                checks.add(new ValidationCheck(operation.name(), ValidationCheck.Outcome.PASS, false,
                        "Proven by a bound compiler receipt"));
                continue;
            }
            CommandPlan plan = plan(context, operation, null);
            if (!plan.available()) {
                checks.add(new ValidationCheck(operation.name(), ValidationCheck.Outcome.SKIPPED, false,
                        plan.unavailableReason()));
                continue;
            }
            var comparison = compareOnce(context, plan);
            comparisons.add(comparison);
            checks.add(new ValidationCheck(operation.name(),
                    comparison.after().started() && !comparison.after().timedOut() && comparison.after().exitCode() == 0
                            ? ValidationCheck.Outcome.PASS
                            : !comparison.after().started() ? ValidationCheck.Outcome.SKIPPED
                                    : ValidationCheck.Outcome.FAIL,
                    true,
                    comparison.changePassed() ? "No new diagnostics" : "Candidate command did not pass"));
            executed.add(operation.name());
        }
        List<ProjectDiagnostic> introduced = new ArrayList<>(
                comparisons.stream().flatMap(value -> value.introduced().stream()).toList());
        List<ProjectDiagnostic> existing = comparisons.stream().flatMap(value -> value.preExisting().stream()).toList();
        boolean passed = comparisons.stream()
                .allMatch(c -> c.after().started() && !c.after().timedOut() && c.after().exitCode() == 0);
        boolean commandsPassed = passed;
        boolean terminal = comparisons.stream().anyMatch(value -> !value.after().started() || value.after().timedOut());
        if (comparisons.stream().anyMatch(c -> c.changePassed() && c.after().exitCode() != 0)) {
            introduced.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                    "The candidate still fails an existing required build check. Fix the existing project before saving this change.",
                    "validation", "PRE_EXISTING_VALIDATION_FAILURE"));
            terminal = true;
        }
        // Only a change that type-checks and builds is worth loading in a browser.
        if (passed) {
            // A retired inherited check changes what the browser must prove without changing the source revision.
            String criteriaKey = criteriaKey(context);
            RuntimeCheckService.Result runtimeResult = criteriaKey.equals(revisionResults(context).runtimeCriteria)
                    ? revisionResults(context).runtime : null;
            if (runtimeResult == null) {
                runtimeResult = runtime == null
                        ? new RuntimeCheckService.Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED,
                                properties.getSandbox().isRuntimeCheck(), "Runtime probe is not wired"), List.of())
                        : runtime.evaluate(context);
                if (runtimeResult == null)
                    throw new IllegalStateException("Runtime check did not report an outcome");
                revisionResults(context).runtime = runtimeResult;
                revisionResults(context).runtimeCriteria = criteriaKey;
            }
            // RUNTIME decides blocking; APPROVED_BEHAVIOUR (checks drafted from the model's spec) is advisory and its
            // WARNING diagnostics are recorded even when RUNTIME passes.
            checks.addAll(runtimeResult.checks());
            introduced.addAll(runtimeResult.diagnostics());
            if (runtimeResult.check().outcome() == ValidationCheck.Outcome.PASS)
                executed.add("RUNTIME");
            if (runtimeResult.check().blocksSuccess()) {
                if (runtimeResult.diagnostics().stream().anyMatch(d -> "PRE_EXISTING_RUNTIME_FAILURE".equals(d.code())))
                    terminal = true;
                passed = false;
                if (runtimeResult.check().outcome() == ValidationCheck.Outcome.SKIPPED) {
                    terminal = true;
                    introduced.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                            runtimeResult.check().reason(), "runtime", "REQUIRED_CHECK_SKIPPED"));
                }
            }
        }
        // API behavior is independent of a failed UI interaction once compilation/build succeeded.
        // Collect real API evidence before selecting repairs; deferred checks are not evidence of a code defect.
        if (commandsPassed && checks.stream().noneMatch(ValidationCheck::unavailable)
                && "mern".equals(stacks.detect(files, context.projectId(), context.root()).id())) {
            var apiChecks = mernBehavior == null ? List.of(new ValidationCheck("API_BEHAVIOR",
                    ValidationCheck.Outcome.SKIPPED, true, "Candidate API validator is not wired"))
                    : mernBehavior.check(context);
            checks.addAll(apiChecks);
            for (var check : apiChecks)
                if (check.blocksSuccess()) {
                    passed = false;
                    introduced.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, check.reason(),
                            "api-behavior",
                            check.outcome() == ValidationCheck.Outcome.SKIPPED ? "REQUIRED_CHECK_SKIPPED"
                                    : "API_ACCEPTANCE_FAILED"));
                    if (check.unavailable() || check.id().endsWith("CLEANUP"))
                        terminal = true;
                }
        }
        var acceptance = behaviorCriteria == null ? null : behaviorCriteria.current(context);
        if (acceptance != null) {
            var missing = new ArrayList<String>();
            if (!acceptance.browser().isEmpty() && checks.stream()
                    .noneMatch(c -> c.id().equals("RUNTIME") && c.outcome() != ValidationCheck.Outcome.SKIPPED))
                missing.add("BROWSER_ACCEPTANCE");
            for (var step : acceptance.api())
                if (checks.stream().noneMatch(c -> c.id().equals("API_" + step.id())))
                    missing.add("API_" + step.id());
            boolean blocked = checks.stream().anyMatch(ValidationCheck::blocksSuccess);
            for (String id : missing) {
                String reason = blocked ? "Earlier validation failed; required acceptance was not run"
                        : "Required acceptance coverage is unavailable";
                checks.add(new ValidationCheck(id, ValidationCheck.Outcome.SKIPPED, true, reason,
                        blocked ? ValidationCheck.SkipCause.PREREQUISITE_FAILED
                                : ValidationCheck.SkipCause.UNAVAILABLE));
                if (!blocked)
                    introduced.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, reason,
                            "acceptance", "REQUIRED_CHECK_SKIPPED"));
            }
            if (!missing.isEmpty()) {
                passed = false;
                if (!blocked)
                    terminal = true;
            }
        }
        if (baseline != null) {
            var missing = introducedBaseline(context, view);
            var blocking = missing.stream().filter(issue -> issue.severity() == DiagnosticSeverity.ERROR).toList();
            checks.add(new ValidationCheck("BASELINE", blocking.isEmpty() ? ValidationCheck.Outcome.PASS
                    : ValidationCheck.Outcome.FAIL, true, blocking.isEmpty()
                            ? missing.isEmpty() ? "Production checklist met" : "No blocking issues; " + missing.size()
                                    + " recommended polish item(s) missing"
                            : "Client code exposes a secret or could not be checked"));
            // Polish items stay visible as warnings; only errors block success.
            introduced.addAll(missing);
            if (!blocking.isEmpty()) {
                passed = false;
            }
        }
        if (terminal)
            changeSets.recordTerminalValidationFailure(context, introduced);
        else
            changeSets.recordProjectValidation(context, passed, introduced);
        String summary = passed
                ? (executed.isEmpty() ? "Structural validation passed; no type-check/build script is configured"
                        : "Validation passed: " + String.join(", ", executed))
                : checks.stream().anyMatch(ValidationCheck::unavailable)
                        ? "Required validation coverage was unavailable; code cannot be certified"
                        : "Validation found errors introduced by the staged changes";
        ValidationReport report = aggregateReport(context, passed, introduced, existing, summary).withChecks(checks);
        if (!passed)
            logDiagnostics(context, ValidationOperation.BUILD, introduced);
        log.info("required_validation runId={} checks={}", context.runId(), checks.stream().map(check -> check.id()
                + ":" + check.outcome() + (check.skipCause() == null ? "" : ":" + check.skipCause())).toList());
        lastReports.put(context.runId(), report);
        return report;
    }

    /** Only items the change broke or a new app lacks; an existing project's old gaps never block an edit. */
    private List<ProjectDiagnostic> introducedBaseline(AgentContext context, ChangeSetService.WorkspaceView view) {
        var before = baseline.check(context.projectId(), view.liveRoot()).stream()
                .map(issue -> issue.code() + "|" + issue.file()).collect(Collectors.toSet());
        return baseline.check(context.projectId(), view.stagedRoot()).stream()
                .filter(issue -> !before.contains(issue.code() + "|" + issue.file())).toList();
    }

    /** First few failing diagnostics of generated code, bounded, so a failed build can be diagnosed from logs. */
    private static void logDiagnostics(AgentContext context, ValidationOperation operation, List<ProjectDiagnostic> diagnostics) {
        diagnostics.stream().limit(3).forEach(d -> log.info("validation_diagnostic runId={} operation={} file={} line={} code={} message={}",
                context.runId(), operation, d.file(), d.line(), d.code(),
                d.message() == null ? "" : d.message().length() > 200 ? d.message().substring(0, 200) + "..." : d.message()));
    }

    /** The stack of the live project: commands come from its original, trusted manifests. */
    private StackPack stack(AgentContext context) {
        return stacks.detect(files, context.projectId(), ".");
    }

    public ValidationReport lastReport(UUID runId) {
        return lastReports.get(runId);
    }

    public void clear(UUID runId) {
        lastReports.remove(runId);
        results.remove(runId);
    }

    /** The comparison for this revision, run only if it has not run yet (or ended without a usable result). */
    private Comparison compareOnce(AgentContext context, CommandPlan plan) {
        RevisionResults current = revisionResults(context);
        Comparison cached = current.comparisons.get(plan.operation());
        if (cached != null) {
            log.info("validation_reused run={} revision={} operation={}", context.runId(), current.revision,
                    plan.operation());
            return cached;
        }
        Comparison comparison = compare(context, plan);
        // A timeout or a sandbox that did not start says nothing about the code: try again next time.
        if (comparison.after().started() && !comparison.after().timedOut())
            current.comparisons.put(plan.operation(), comparison);
        return comparison;
    }

    /** The acceptance the browser check runs: the run's own criteria, else the inherited regression checks. */
    private String criteriaKey(AgentContext context) {
        if (behaviorCriteria == null)
            return "";
        var own = behaviorCriteria.current(context);
        return "own:" + (own != null ? own : "") + "|inherited:" + (own != null ? "" : behaviorCriteria.regression(context));
    }

    private RevisionResults revisionResults(AgentContext context) {
        int revision = changeSets.view(context).revision();
        return results.compute(context.runId(),
                (run, existing) -> existing != null && existing.revision == revision ? existing
                        : new RevisionResults(revision));
    }

    private static final class RevisionResults {
        final int revision;
        final Map<ValidationOperation, Comparison> comparisons = new ConcurrentHashMap<>();
        volatile RuntimeCheckService.Result runtime;
        volatile String runtimeCriteria;

        RevisionResults(int revision) {
            this.revision = revision;
        }
    }

    private Comparison compare(AgentContext context, CommandPlan plan) {
        String name = plan.operation().name().toLowerCase(Locale.ROOT);
        String baseline = changeSets.createValidationBaseline(context, name);
        try {
            String candidate = changeSets.createValidationCandidate(context, name);
            try {
                stack(context).preserveTrustedCommands(files, context.projectId(), ".", candidate);
                List<String> touched = changeSets.view(context).touchedPaths();
                DependencyService.Preparation baselineDeps = dependencies.prepare(context.projectId(), baseline);
                DependencyService.Preparation candidateDeps = dependencies.prepare(context.projectId(), candidate);
                CommandOutcome before = baselineDeps.failed() ? notStarted(context, baseline, baselineDeps.error())
                        : execute(context, plan, baseline);
                if (candidateDeps.failed()) {
                    if (baselineDeps.failed()) {
                        // The project's own dependencies do not install either (registry down, or already broken):
                        // nothing the change can be blamed for, and nothing it can repair.
                        CommandOutcome after = notStarted(context, candidate, candidateDeps.error());
                        return new Comparison(plan, before, after,
                                List.of(infrastructureDiagnostic(after, plan.operation())), List.of(), false);
                    }
                    CommandOutcome after = new CommandOutcome(true, false, 1, candidateDeps.error(),
                            sandbox.commandPath(context.projectId(), candidate));
                    return new Comparison(plan, before, after, List.of(new ProjectDiagnostic(
                            "package.json", null, null, DiagnosticSeverity.ERROR,
                            truncate(candidateDeps.error(), 1_500),
                            "dependencies", "DEPENDENCY_INSTALL_FAILED")), List.of(), false);
                }
                CommandOutcome after = execute(context, plan, candidate);
                List<ProjectDiagnostic> baselineDiagnostics = diagnostics(before, plan.operation(), touched);
                List<ProjectDiagnostic> currentDiagnostics = diagnostics(after, plan.operation(), touched);
                Set<String> baselineKeys = baselineDiagnostics.stream()
                        .map(ProjectValidationService::responsibilityFingerprint)
                        .collect(Collectors.toSet());
                List<ProjectDiagnostic> introduced = currentDiagnostics.stream()
                        .filter(item -> !baselineKeys.contains(responsibilityFingerprint(item))).toList();
                List<ProjectDiagnostic> existing = currentDiagnostics.stream()
                        .filter(item -> baselineKeys.contains(responsibilityFingerprint(item))).toList();
                boolean passed;
                if (!after.started() || after.timedOut())
                    passed = false;
                else if (after.exitCode() == 0)
                    passed = true;
                else
                    passed = before.started() && !before.timedOut() && before.exitCode() != 0 && introduced.isEmpty();
                if (!passed && introduced.isEmpty())
                    introduced = List.of(infrastructureDiagnostic(after, plan.operation()));
                List<ProjectDiagnostic> boundedIntroduced = bounded(introduced, touched);
                int remaining = Math.max(0, properties.getMaxValidationDiagnostics() - boundedIntroduced.size());
                return new Comparison(plan, before, after, boundedIntroduced, bounded(existing, touched, remaining),
                        passed);
            } finally {
                try {
                    sandbox.removeTree(context.projectId(), candidate);
                } catch (RuntimeException ignored) {
                }
            }
        } finally {
            try {
                sandbox.removeTree(context.projectId(), baseline);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private CommandOutcome notStarted(AgentContext context, String root, String reason) {
        return new CommandOutcome(false, false, -1, reason, sandbox.commandPath(context.projectId(), root));
    }

    /**
     * The command for an operation, from the live project's trusted manifest. A project's first build has
     * none, so the staged stack's fixed commands are used instead (never the staged scripts).
     */
    private CommandPlan plan(AgentContext context, ValidationOperation operation, String scope) {
        StackPack live = stack(context);
        CommandPlan plan = live.resolve(files, context.projectId(), ".", operation, scope);
        if (plan.available())
            return plan;
        String staged = changeSets.view(context).stagedRoot();
        // Asked even for the same pack: a first static build adds a pinned compiler the legacy live root lacks.
        StackPack next = stacks.detect(files, context.projectId(), staged);
        CommandPlan fixed = next.resolveFixed(files, context.projectId(), staged, operation);
        return fixed.available() ? fixed : plan;
    }

    private CommandOutcome execute(AgentContext context, CommandPlan plan, String root) {
        long started = System.nanoTime();
        var result = sandbox.exec(context.projectId(), root, plan.command());
        log.info("validation_command runId={} operation={} root={} command=\"{}\" exitCode={} elapsedMs={}",
                context.runId(),
                plan.operation(), root.substring(root.lastIndexOf('/') + 1), String.join(" ", plan.command()),
                result.exitCode(),
                (System.nanoTime() - started) / 1_000_000);
        String output = result.stdout() + (result.error().isBlank() ? "" : "\n" + result.error());
        return new CommandOutcome(result.started(), result.timedOut(), result.exitCode(), output,
                sandbox.commandPath(context.projectId(), root));
    }

    private List<ProjectDiagnostic> diagnostics(CommandOutcome outcome, ValidationOperation operation,
            List<String> touched) {
        List<ProjectDiagnostic> diagnostics = new ArrayList<>(
                ValidationOutputParser.parse(outcome.output(), outcome.root(), operation));
        if (outcome.exitCode() != 0 && diagnostics.isEmpty() && !outcome.output().isBlank()) {
            diagnostics.add(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                    ValidationOutputParser.summary(outcome.output(), outcome.root()),
                    operation.name().toLowerCase(Locale.ROOT), null));
        }
        return bounded(diagnostics, touched);
    }

    private List<ProjectDiagnostic> bounded(List<ProjectDiagnostic> input, List<String> touched) {
        return bounded(input, touched, Math.max(1, properties.getMaxValidationDiagnostics()));
    }

    private List<ProjectDiagnostic> bounded(List<ProjectDiagnostic> input, List<String> touched, int limit) {
        if (limit <= 0)
            return List.of();
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
                comparison.changePassed() ? "No new diagnostics were introduced"
                        : "Staged changes introduced validation errors");
    }

    private ValidationReport withProgress(ValidationReport report, ChangeSetService.ValidationProgress progress) {
        return new ValidationReport(report.operation(), report.available(), report.commandSucceeded(),
                report.changeValidationPassed(),
                report.timedOut(), report.exitCode(), report.packageManager(), report.script(),
                report.introducedDiagnostics(),
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
                stack(context).inspect(files, context.projectId(), view.liveRoot()).packageManager(),
                "project validation",
                boundedIntroduced, bounded(existing, view.touchedPaths(), remaining), progress.repairAttempts(),
                progress.repairAttemptsRemaining(), progress.revision(), summary);
    }

    private ValidationReport reportUnavailable(AgentContext context, CommandPlan plan) {
        var progress = changeSets.validationProgress(context);
        return new ValidationReport(plan.operation(), false, false, false, false, null, plan.packageManager(), null,
                List.of(), List.of(), progress.repairAttempts(), progress.repairAttemptsRemaining(),
                progress.revision(),
                plan.unavailableReason());
    }

    private static ProjectDiagnostic infrastructureDiagnostic(CommandOutcome outcome, ValidationOperation operation) {
        String message = outcome.timedOut() ? "Validation timed out"
                : (!outcome.started()
                        ? "Validation executable could not be started"
                        : "Validation failed without a parseable diagnostic");
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, message,
                operation.name().toLowerCase(Locale.ROOT), null);
    }

    private static void validateScope(String scope) {
        if (scope == null || scope.isBlank())
            return;
        if (scope.length() > 200 || scope.startsWith("-") || scope.indexOf('\0') >= 0 || Path.of(scope).isAbsolute()) {
            throw new AgentToolException("INVALID_TEST_SCOPE", "Test scope must be a short relative path or test name");
        }
        for (Path part : Path.of(scope))
            if (part.toString().equals("..")) {
                throw new AgentToolException("INVALID_TEST_SCOPE",
                        "Parent path traversal is not allowed in test scope");
            }
        if (!scope.matches("[A-Za-z0-9_./\\\\:-]+")) {
            throw new AgentToolException("INVALID_TEST_SCOPE", "Test scope contains unsupported characters");
        }
    }

    private static String responsibilityFingerprint(ProjectDiagnostic item) {
        return String.join("|", item.file() == null ? "" : item.file(), item.severity().name(),
                item.message(), item.source(), item.code() == null ? "" : item.code());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    /** {@code root}: the directory the command ran in, as the command saw it (for mapping its paths back). */
    private record CommandOutcome(boolean started, boolean timedOut, int exitCode, String output, String root) {
    }

    private record Comparison(CommandPlan plan, CommandOutcome before, CommandOutcome after,
            List<ProjectDiagnostic> introduced, List<ProjectDiagnostic> preExisting,
            boolean changePassed) {
    }

    public record CapabilitySummary(String stack, String packageManager, String declaredPackageManager,
            List<String> scriptNames,
            List<String> availableOperations, String error) {
    }

    public record DiagnosticsSnapshot(CapabilitySummary capabilities, ValidationReport lastReport,
            ChangeSetService.ValidationProgress progress) {
    }
}
