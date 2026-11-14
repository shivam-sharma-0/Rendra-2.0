package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.preview.FrontendArtifactCache;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.workspace.SupabaseScratch;
import com.code.pal.agent.vision.GeneratedVisualReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Loads the staged site in a headless browser and reports what breaks at runtime: uncaught exceptions,
 * console errors, missing assets, a page that renders nothing. Type checks and builds cannot see these.
 *
 * <p>Only problems the change introduced are reported: when the staged site has issues, the live
 * project is checked the same way and its issues are subtracted. Missing coverage is explicitly
 * SKIPPED; the final gate blocks a required skip.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuntimeCheckService {
    private static final int MAX_MESSAGE = 500;

    private final ChangeSetService changeSets;
    private final StackPacks stacks;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final DependencyService dependencies;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final BuildVariables variables;
    private RunBehaviorCriteria behaviorCriteria;

    @Autowired
    public void useBehaviorCriteria(RunBehaviorCriteria criteria) {
        this.behaviorCriteria = Objects.requireNonNull(criteria);
    }

    private CandidateBrowserCheck candidateBrowser;
    private GeneratedVisualReview visualReview;
    private CandidateSupabaseValidation supabaseValidation;
    private TrustedFeatureOutcomes featureOutcomes;
    private FrontendArtifactCache artifactCache;

    @Autowired
    public void useArtifactCache(FrontendArtifactCache cache) { artifactCache = Objects.requireNonNull(cache); }

    @Autowired
    public void useFeatureOutcomes(TrustedFeatureOutcomes outcomes) { featureOutcomes = Objects.requireNonNull(outcomes); }

    @Autowired
    public void useSupabaseValidation(CandidateSupabaseValidation validation) {
        supabaseValidation = Objects.requireNonNull(validation);
    }

    @Autowired
    public void useVisualReview(GeneratedVisualReview review) {
        visualReview = review;
    }

    @Autowired
    public void useCandidateBrowser(CandidateBrowserCheck browser) {
        this.candidateBrowser = Objects.requireNonNull(browser);
    }

    public record Result(ValidationCheck check, List<ProjectDiagnostic> diagnostics, ValidationCheck approvedBehaviour,
            List<ValidationCheck> additionalChecks) {
        public Result(ValidationCheck check, List<ProjectDiagnostic> diagnostics, ValidationCheck approvedBehaviour) {
            this(check, diagnostics, approvedBehaviour, List.of());
        }

        public Result(ValidationCheck check, List<ProjectDiagnostic> diagnostics) {
            this(check, diagnostics, null);
        }

        public Result {
            diagnostics = List.copyOf(diagnostics);
            additionalChecks = List.copyOf(additionalChecks);
        }

        /** Keep advisory checks in the report even when the required runtime check passes. */
        public List<ValidationCheck> checks() {
            var result = new ArrayList<ValidationCheck>();
            result.add(check);
            if (approvedBehaviour != null) result.add(approvedBehaviour);
            result.addAll(additionalChecks);
            return List.copyOf(result);
        }
    }

    /** Legacy diagnostics projection; use evaluate for coverage and final certification. */
    public List<ProjectDiagnostic> check(AgentContext context) {
        return evaluate(context).diagnostics();
    }

    public String coverageKey(AgentContext context) {
        return (supabaseValidation == null ? "" : supabaseValidation.bindingKey(context)) + "|features:"
                + (featureOutcomes == null ? "" : featureOutcomes.plan(context.projectId()).binding());
    }

    public Result evaluate(AgentContext context) {
        var outcomePlan = featureOutcomes == null ? TrustedFeatureOutcomes.Plan.EMPTY : featureOutcomes.plan(context.projectId());
        if (!properties.getSandbox().isRuntimeCheck())
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, false,
                    "Explicitly disabled by backend configuration"), List.of(), null,
                    unavailableCoverage(context, outcomePlan));
        if (apiOnly(context))
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                    "Unsupported scope: an API-only app has no page for browser acceptance"), List.of());
        var candidate = probe(context, true, outcomePlan);
        var additional = new ArrayList<>(candidate.checks());
        Probe staged = candidate.value();
        if (staged != null) additional.addAll(staged.outcomes());
        else if (featureOutcomes != null) additional.addAll(featureOutcomes.checks(outcomePlan, null));
        if (featureOutcomes != null && !outcomePlan.binding().equals(featureOutcomes.plan(context.projectId()).binding())) {
            additional.add(new ValidationCheck("FEATURE_APPROVAL", ValidationCheck.Outcome.SKIPPED, true,
                    "Approved feature scope or outcome policy changed during validation"));
        }
        if (staged == null)
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                    candidate.checks().stream().anyMatch(ValidationCheck::blocksSuccess)
                            ? "Candidate browser could not run because isolated cloud prerequisites did not pass"
                            : "Candidate runtime probe was unavailable or unreadable",
                    candidate.checks().stream().anyMatch(ValidationCheck::blocksSuccess)
                            ? ValidationCheck.SkipCause.PREREQUISITE_FAILED : ValidationCheck.SkipCause.UNAVAILABLE),
                    List.of(), null, additional);
        var functional = new Probe(staged.issues().stream().filter(issue -> !issue.kind().equals("visual")).toList(),
                staged.interactions(), staged.modelSpec(), staged.failedActions(), staged.blockedActions());
        var runtime = runtimeResult(context, functional, candidate.applicable());
        var quality = new ArrayList<>(runtime.diagnostics());
        staged.issues().stream().filter(issue -> issue.kind().equals("visual"))
                .map(RuntimeCheckService::diagnostic).forEach(quality::add);
        runtime = new Result(runtime.check(), quality, null, additional);
        if (!staged.modelSpec()) {
            return runtime;
        }
        var warnings = new ArrayList<>(runtime.diagnostics());
        for (String id : staged.failedActions()) {
            warnings.add(modelDiagnostic(id, false));
        }
        for (String id : staged.blockedActions()) {
            warnings.add(modelDiagnostic(id, true));
        }
        boolean passed = staged.failedActions().isEmpty() && staged.blockedActions().isEmpty();
        String reason = "MODEL_SPEC; failed_scenarios=" + scenarios(staged.failedActions())
                + "; blocked_scenarios=" + scenarios(staged.blockedActions());
        var advisory = new ValidationCheck("APPROVED_BEHAVIOUR",
                passed ? ValidationCheck.Outcome.PASS : ValidationCheck.Outcome.FAIL, false, reason);
        log.info("approved_behaviour runId={} outcome={} reason={}", context.runId(), advisory.outcome(), reason);
        return new Result(runtime.check(), warnings, advisory, additional);
    }

    private List<ValidationCheck> unavailableCoverage(AgentContext context, TrustedFeatureOutcomes.Plan plan) {
        var checks = new ArrayList<ValidationCheck>();
        if (supabaseValidation != null) checks.addAll(supabaseValidation.browserDisabledChecks(context));
        if (featureOutcomes != null) checks.addAll(featureOutcomes.checks(plan, null));
        return List.copyOf(checks);
    }

    private Result runtimeResult(AgentContext context, Probe staged, boolean isolatedCloud) {
        if (staged.issues().stream().anyMatch(issue -> issue.kind().equals("api-unavailable")))
            return new Result(
                    new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                            "Candidate API transport was unavailable; code repair cannot resolve infrastructure"),
                    List.of());
        if (staged.issues().isEmpty())
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.PASS, true,
                    "Candidate loaded without new runtime issues"), List.of());
        // A cloud baseline must never fall back to the saved customer's connector or inherit scratch state.
        Probe live = isolatedCloud ? null : probe(context, false, TrustedFeatureOutcomes.Plan.EMPTY).value();
        Set<String> existing = live == null ? Set.of()
                : live.issues().stream().map(RuntimeCheckService::fingerprint).collect(Collectors.toSet());
        List<ProjectDiagnostic> introduced = staged.issues().stream()
                .filter(issue -> !existing.contains(fingerprint(issue)))
                .map(RuntimeCheckService::diagnostic).toList();
        introduced.forEach(
                issue -> log.info("runtime_check_issue runId={} code={} message={}", context.runId(), issue.code(),
                        issue.message().length() > 300 ? issue.message().substring(0, 300) + "..." : issue.message()));
        if (introduced.isEmpty())
            return new Result(
                    new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true,
                            "Candidate still has existing runtime failures"),
                    List.of(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                            "Existing runtime problems still prevent a working preview", "runtime",
                            "PRE_EXISTING_RUNTIME_FAILURE")));
        return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true,
                "Candidate introduced runtime issues"), introduced);
    }

    /** {@code action}: the control being exercised when it happened (e.g. button "Reset"), null while loading. */
    record Issue(String kind, String message, String source, String action) {
        Issue(String kind, String message, String source) {
            this(kind, message, source, null);
        }
    }

    /** {@code interactions}: how many controls the probe exercised after the page loaded. */
    private record Probe(List<Issue> issues, int interactions, boolean modelSpec, List<String> failedActions,
            List<String> blockedActions, List<ValidationCheck> outcomes) {
        Probe(List<Issue> issues, int interactions, boolean modelSpec, List<String> failedActions, List<String> blockedActions) {
            this(issues, interactions, modelSpec, failedActions, blockedActions, List.of());
        }
        Probe(List<Issue> issues, int interactions) {
            this(issues, interactions, false, List.of(), List.of());
        }
    }

    /** Builds (if the stack needs it) a copy of the staged or live project and loads it; null if skipped. */
    private CandidateSupabaseValidation.Result<Probe> probe(AgentContext context, boolean staged, TrustedFeatureOutcomes.Plan outcomePlan) {
        UUID projectId = context.projectId();
        String copy = staged ? changeSets.createValidationCandidate(context, "runtime")
                : changeSets.createValidationBaseline(context, "runtime");
        long started = System.nanoTime();
        try {
            return staged && supabaseValidation != null
                    ? supabaseValidation.evaluate(context, copy, cloud -> probeCopy(context, staged, copy, cloud, started, outcomePlan))
                    : new CandidateSupabaseValidation.Result<>(probeCopy(context, staged, copy, null, started, outcomePlan), List.of(), false);
        } finally {
            try {
                sandbox.removeTree(projectId, copy);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private Probe probeCopy(AgentContext context, boolean staged, String copy, SupabaseScratch.Session cloud, long started,
            TrustedFeatureOutcomes.Plan outcomePlan) {
        String site = site(context, copy, cloud);
        if (site == null)
            return null;
        BehaviorCriteria criteria = staged && behaviorCriteria != null ? behaviorCriteria.current(context) : null;
        boolean inherited = false;
        if (staged && behaviorCriteria != null && criteria == null) {
            criteria = behaviorCriteria.regression(context);
            inherited = criteria != null;
        }
        var actions = criteria == null ? List.<BehaviorCriteria.BrowserAction>of() : criteria.browser();
        boolean mern = "mern".equals(stacks.detect(files, context.projectId(), copy).id());
        var auth = mern ? AccountAcceptance.browserSession(criteria, context.projectId()) : Map.<String, String>of();
        var browserData = new LinkedHashMap<String, Object>(Map.of("schema", 1, "actions", actions));
        var smokeData = new LinkedHashMap<String, Object>(outcomePlan.definitions().isEmpty()
                ? Map.of("schema", 1, "actions", List.of()) : featureOutcomes.browserData(outcomePlan, List.of()));
        if (!auth.isEmpty()) { browserData.put("authSetup", auth); smokeData.put("authSetup", auth); }
        String acceptance = objectMapper.writeValueAsString(browserData);
        String smokeAcceptance = outcomePlan.definitions().isEmpty() && auth.isEmpty() ? null : objectMapper.writeValueAsString(smokeData);
        Probe smoke = null;
        SandboxPort.CommandResult outcomeResult = null;
        if (!actions.isEmpty() && cloud == null) {
            // A separate fresh browser keeps generic control smoke mandatory without dirtying the spec recipe's state.
            // Both passes use this same built site; do not rebuild the candidate between them.
            var smokeResult = browse(context, copy, site, smokeAcceptance, mern, cloud);
            outcomeResult = smokeResult;
            smoke = readable(smokeResult) ? parse(smokeResult.stdout()) : null;
            if (smoke == null) {
                log.warn("runtime_check_skipped runId={} phase=smoke reason=unavailable_or_unreadable", context.runId());
                return null;
            }
        }
        var result = browse(context, copy, site, actions.isEmpty() ? smokeAcceptance : acceptance, mern, cloud);
        if (outcomeResult == null) outcomeResult = result;
        if (!readable(result)) {
            log.warn("runtime_check_skipped runId={} phase=recipe reason=unavailable", context.runId());
            return null;
        }
        Probe probe = parse(result.stdout());
        var visualResult = result;
        if (!actions.isEmpty() && cloud != null) {
            // Recipe first: generic clicks can write to the same scratch database. A fresh browser still
            // runs independent smoke afterwards, without altering the recipe's initial data or expected counts.
            var smokeResult = browse(context, copy, site, smokeAcceptance, mern, cloud);
            outcomeResult = smokeResult;
            smoke = readable(smokeResult) ? parse(smokeResult.stdout()) : null;
            if (smoke == null) return null;
            visualResult = smokeResult;
        }
        if (staged && probe != null && visualReview != null) {
            String report = visualResult.stdout().lines().filter(line -> line.startsWith("{"))
                    .reduce((first, last) -> last).orElse("{}");
            visualReview.record(context, objectMapper.readTree(report));
        }
        if (probe != null && !actions.isEmpty()) {
            JsonNode json = objectMapper.readTree(result.stdout().lines().filter(line -> line.startsWith("{"))
                    .reduce((first, last) -> last).orElse("{}"));
            var actual = json.path("acceptance");
            if (!actual.isArray() || actual.size() != actions.size())
                return null;
            List<Issue> checked = new ArrayList<>(probe.issues());
            checked.addAll(smoke.issues());
            var failedActions = new ArrayList<String>();
            var blockedActions = new ArrayList<String>();
            boolean modelSpec = criteria.modelSpec();
            boolean prerequisiteFailed = false;
            for (int i = 0; i < actions.size(); i++) {
                var a = actual.get(i);
                if (!actions.get(i).id().equals(a.path("id").asString()) || !a.path("passed").isBoolean())
                    return null;
                if (a.path("prerequisiteFailed").asBoolean(false)) {
                    if (!prerequisiteFailed || a.path("passed").asBoolean())
                        return null;
                    if (modelSpec) {
                        blockedActions.add(actions.get(i).id());
                    }
                    continue;
                }
                if (!a.path("passed").asBoolean()) {
                    prerequisiteFailed = true;
                    if (modelSpec) {
                        failedActions.add(actions.get(i).id());
                    } else {
                        checked.add(new Issue("acceptance", inherited
                                ? "Earlier approved check " + actions.get(i).id() + " no longer passes: keep that feature "
                                        + "working, or call retireAcceptanceCheck with this id if the user asked to change it"
                                : actions.get(i).failureMessage() + (a.path("observed").isString()
                                        ? " (" + a.path("observed").asString() + ")" : ""), null));
                    }
                }
            }
            probe = new Probe(List.copyOf(checked), probe.interactions() + smoke.interactions(), modelSpec,
                    List.copyOf(failedActions), List.copyOf(blockedActions));
        }
        if (probe != null && featureOutcomes != null) {
            String report = outcomeResult.stdout().lines().filter(line -> line.startsWith("{")).reduce((first, last) -> last).orElse("{}");
            probe = new Probe(probe.issues(), probe.interactions(), probe.modelSpec(), probe.failedActions(), probe.blockedActions(),
                    featureOutcomes.checks(outcomePlan, objectMapper.readTree(report)));
        }
        log.info("runtime_check runId={} target={} issues={} interactions={} elapsedMs={}", context.runId(),
                staged ? "staged" : "live",
                probe == null ? "unreadable" : probe.issues().size(), probe == null ? 0 : probe.interactions(),
                (System.nanoTime() - started) / 1_000_000);
        return probe;
    }

    private SandboxPort.CommandResult browse(AgentContext context, String copy, String site, String acceptance, boolean mern,
            SupabaseScratch.Session cloud) {
        if (cloud != null) return sandbox.browseWithSupabase(context.projectId(), site,
                acceptance == null ? objectMapper.writeValueAsString(Map.of("schema", 1, "actions", List.of())) : acceptance, cloud.endpoint());
        if (mern) {
            return candidateBrowser == null
                    ? new SandboxPort.CommandResult(false, -1, "", SandboxPort.CommandResult.NOT_STARTED + ": candidate API browser not wired")
                    : candidateBrowser.browse(context, copy, site,
                            acceptance == null ? objectMapper.writeValueAsString(Map.of("schema", 1, "actions", List.of())) : acceptance);
        }
        return acceptance == null ? sandbox.browse(context.projectId(), site) : sandbox.browse(context.projectId(), site, acceptance);
    }

    private static boolean readable(SandboxPort.CommandResult result) {
        return result != null && result.started() && result.success();
    }

    private static String scenarios(List<String> actions) {
        var ids = new LinkedHashSet<String>();
        actions.forEach(id -> ids.add(id.replaceFirst("_[0-9]+$", "")));
        return String.join(",", ids);
    }

    private static ProjectDiagnostic modelDiagnostic(String id, boolean blocked) {
        String message = blocked ? "Approved behaviour check " + id + " was not run because an earlier step failed"
                : "Approved behaviour check " + id + " did not pass; the requested behaviour could not be confirmed";
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.WARNING, message, "approved-behaviour",
                blocked ? "MODEL_SPEC_ASSERTION_BLOCKED" : "MODEL_SPEC_ASSERTION_FAILED");
    }

    /** The folder to load: the readable build output, or the files themselves for a static site. */
    private String site(AgentContext context, String copy, SupabaseScratch.Session cloud) {
        UUID projectId = context.projectId();
        StackPack stack = stacks.detect(files, projectId, copy);
        StackPack.PreviewBuild build = stack.diagnosticBuild(files, projectId, copy);
        if (build == null)
            return files.regularFile(projectId, copy, "index.html") ? copy : null;
        stack.applyBuildVariables(files, projectId, copy, cloud == null ? variables.variables(projectId) : cloud.buildVariables());
        // A broken install or build is already reported by validation; nothing to load then.
        if (dependencies.prepare(projectId, copy).failed())
            return null;
        stack.prepareBuild(files, projectId, copy);
        var artifactToken = artifactCache != null && cloud == null ? artifactCache.token(context, copy, stack, build) : null;
        String restored = artifactToken == null ? null : artifactCache.restore(artifactToken, context, copy, stack, build);
        if (restored != null) {
            log.info("runtime_build_reused runId={} stack={}", context.runId(), stack.id());
            return restored;
        }
        if (!sandbox.exec(projectId, copy, build.command()).success())
            return null;
        if (artifactToken != null) artifactCache.capture(artifactToken, context, copy, stack, build);
        return build.outputDirectories().stream().map(directory -> copy + "/" + directory)
                .filter(directory -> files.regularFile(projectId, directory, "index.html")).findFirst().orElse(null);
    }

    private Probe parse(String stdout) {
        String json = stdout.lines().filter(line -> line.startsWith("{")).reduce((first, last) -> last).orElse(null);
        if (json == null)
            return null;
        try {
            JsonNode report = objectMapper.readTree(json);
            if (report.hasNonNull("error")) {
                log.warn("runtime_check_probe_error reason={}", report.get("error").asText());
                return null;
            }
            if (json.length() > 64_000 || !report.path("loaded").isBoolean() || !report.path("issues").isArray()
                    || report.path("issues").size() > 50 || !report.path("interactions").isArray()
                    || report.path("interactions").size() > 20)
                return null;
            List<Issue> issues = new ArrayList<>();
            if (!report.path("loaded").asBoolean())
                issues.add(new Issue("load", "Candidate page did not finish loading", null));
            for (JsonNode issue : report.path("issues")) {
                if (!issue.isObject() || !issue.path("kind").isString() || !issue.path("message").isString())
                    return null;
                issues.add(new Issue(issue.path("kind").asText("console"), issue.path("message").asText(""),
                        issue.path("source").isNull() ? null : issue.path("source").asText(null),
                        issue.path("action").isNull() ? null : issue.path("action").asText(null)));
            }
            if (report.path("blank").asBoolean(false) && issues.stream().noneMatch(issue -> issue.kind().equals("blank"))) {
                issues.add(new Issue("blank", "Candidate page was blank", null));
            }
            return new Probe(List.copyOf(issues), report.path("interactions").size());
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** A server with no web page is out of browser scope; it is reported, never passed as a browser check. */
    private boolean apiOnly(AgentContext context) {
        var view = changeSets.view(context);
        if (view == null)
            return false;
        String staged = view.stagedRoot();
        UUID projectId = context.projectId();
        return files.regularFile(projectId, staged, "server/package.json")
                && !files.regularFile(projectId, staged, "client/package.json")
                && !files.regularFile(projectId, staged, "index.html");
    }

    /** Same problem, different build: asset hashes, line and column numbers and ports do not count. */
    static String fingerprint(Issue issue) {
        String message = issue.message().split("\\R", 2)[0]
                .replaceAll("-[A-Za-z0-9_-]{8}\\.(js|css|mjs)", ".$1")
                .replaceAll(":\\d+(:\\d+)?", "")
                .trim();
        return issue.kind() + "|" + message + (issue.action() == null ? "" : "|" + issue.action());
    }

    private static ProjectDiagnostic diagnostic(Issue issue) {
        String code = switch (issue.kind()) {
        case "exception" -> "RUNTIME_EXCEPTION";
        case "asset" -> "MISSING_ASSET";
        case "blank" -> "BLANK_PAGE";
        case "load" -> "PAGE_NOT_LOADED";
        case "acceptance" -> "BROWSER_ACCEPTANCE_FAILED";
        case "layout" -> "MOBILE_OVERFLOW";
        case "contrast" -> "TEXT_CONTRAST";
        case "visual" -> "UI_ACCESSIBILITY";
        default -> "CONSOLE_ERROR";
        };
        String message = switch (issue.kind()) {
        case "blank" ->
            "The page rendered nothing visible: check that the entry script runs and mounts the app into index.html";
        case "asset" -> "A file the page references is missing: " + issue.message();
        default -> issue.message() + (issue.source() == null ? "" : " (at " + issue.source() + ")");
        };
        if (issue.action() != null)
            message = "After the check clicked/typed into " + issue.action() + ": " + message;
        String bounded = message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE) + "...";
        return new ProjectDiagnostic(null, null, null,
                issue.kind().equals("visual") ? DiagnosticSeverity.WARNING : DiagnosticSeverity.ERROR, bounded, "runtime", code);
    }
}
