package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.code.pal.agent.preview.BuildVariables;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired
    public void useCandidateBrowser(CandidateBrowserCheck browser) {
        this.candidateBrowser = Objects.requireNonNull(browser);
    }

    public record Result(ValidationCheck check, List<ProjectDiagnostic> diagnostics) {
        public Result {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Legacy diagnostics projection; use evaluate for coverage and final certification. */
    public List<ProjectDiagnostic> check(AgentContext context) {
        return evaluate(context).diagnostics();
    }

    public Result evaluate(AgentContext context) {
        if (!properties.getSandbox().isRuntimeCheck())
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, false,
                    "Explicitly disabled by backend configuration"), List.of());
        if (apiOnly(context))
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                    "Unsupported scope: an API-only app has no page for browser acceptance"), List.of());
        Probe staged = probe(context, true);
        if (staged == null)
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                    "Candidate runtime probe was unavailable or unreadable"), List.of());
        if (staged.issues().stream().anyMatch(issue -> issue.kind().equals("api-unavailable")))
            return new Result(
                    new ValidationCheck("RUNTIME", ValidationCheck.Outcome.SKIPPED, true,
                            "Candidate API transport was unavailable; code repair cannot resolve infrastructure"),
                    List.of());
        if (staged.issues().isEmpty())
            return new Result(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.PASS, true,
                    "Candidate loaded without new runtime issues"), List.of());
        Probe live = probe(context, false);
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
    private record Probe(List<Issue> issues, int interactions) {
    }

    /** Builds (if the stack needs it) a copy of the staged or live project and loads it; null if skipped. */
    private Probe probe(AgentContext context, boolean staged) {
        UUID projectId = context.projectId();
        String copy = staged ? changeSets.createValidationCandidate(context, "runtime")
                : changeSets.createValidationBaseline(context, "runtime");
        long started = System.nanoTime();
        try {
            String site = site(projectId, copy);
            if (site == null)
                return null;
            BehaviorCriteria criteria = staged && behaviorCriteria != null ? behaviorCriteria.current(context) : null;
            boolean inherited = false;
            if (staged && behaviorCriteria != null && criteria == null) {
                criteria = behaviorCriteria.regression(context);
                inherited = criteria != null;
            }
            var actions = criteria == null ? List.<BehaviorCriteria.BrowserAction>of() : criteria.browser();
            String acceptance = objectMapper.writeValueAsString(Map.of("schema", 1, "actions", actions));
            boolean mern = "mern".equals(stacks.detect(files, projectId, copy).id());
            SandboxPort.CommandResult result = mern
                    ? candidateBrowser == null
                            ? new SandboxPort.CommandResult(false, -1, "",
                                    SandboxPort.CommandResult.NOT_STARTED + ": candidate API browser not wired")
                            : candidateBrowser.browse(context, copy, site, acceptance)
                    : actions.isEmpty() ? sandbox.browse(projectId, site) : sandbox.browse(projectId, site, acceptance);
            if (!result.started() || !result.success()) {
                log.warn("runtime_check_skipped runId={} reason={}", context.runId(),
                        result.error().isBlank() ? result.stdout() : result.error());
                return null;
            }
            Probe probe = parse(result.stdout());
            if (probe != null && !actions.isEmpty()) {
                JsonNode json = objectMapper.readTree(result.stdout().lines().filter(line -> line.startsWith("{"))
                        .reduce((first, last) -> last).orElse("{}"));
                var actual = json.path("acceptance");
                if (!actual.isArray() || actual.size() != actions.size())
                    return null;
                List<Issue> checked = new ArrayList<>(probe.issues());
                boolean prerequisiteFailed = false;
                for (int i = 0; i < actions.size(); i++) {
                    var a = actual.get(i);
                    if (!actions.get(i).id().equals(a.path("id").asString()) || !a.path("passed").isBoolean())
                        return null;
                    if (a.path("prerequisiteFailed").asBoolean(false)) {
                        if (!prerequisiteFailed || a.path("passed").asBoolean())
                            return null;
                        continue;
                    }
                    if (!a.path("passed").asBoolean()) {
                        prerequisiteFailed = true;
                        checked.add(new Issue("acceptance", inherited
                                ? "Earlier approved check " + actions.get(i).id() + " no longer passes: keep that feature "
                                        + "working, or call retireAcceptanceCheck with this id if the user asked to change it"
                                : "Required interaction " + actions.get(i).id() + " did not pass", null));
                    }
                }
                probe = new Probe(List.copyOf(checked), probe.interactions());
            }
            log.info("runtime_check runId={} target={} issues={} interactions={} elapsedMs={}", context.runId(),
                    staged ? "staged" : "live",
                    probe == null ? "unreadable" : probe.issues().size(), probe == null ? 0 : probe.interactions(),
                    (System.nanoTime() - started) / 1_000_000);
            return probe;
        } finally {
            try {
                sandbox.removeTree(projectId, copy);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** The folder to load: the readable build output, or the files themselves for a static site. */
    private String site(UUID projectId, String copy) {
        StackPack stack = stacks.detect(files, projectId, copy);
        StackPack.PreviewBuild build = stack.diagnosticBuild(files, projectId, copy);
        if (build == null)
            return files.regularFile(projectId, copy, "index.html") ? copy : null;
        stack.applyBuildVariables(files, projectId, copy, variables.variables(projectId));
        // A broken install or build is already reported by validation; nothing to load then.
        if (dependencies.prepare(projectId, copy).failed())
            return null;
        if (!sandbox.exec(projectId, copy, build.command()).success())
            return null;
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
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, bounded, "runtime", code);
    }
}
