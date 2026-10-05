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

/**
 * Loads the staged site in a headless browser and reports what breaks at runtime: uncaught exceptions,
 * console errors, missing assets, a page that renders nothing. Type checks and builds cannot see these.
 *
 * <p>Only problems the change introduced are reported: when the staged site has issues, the live
 * project is checked the same way and its issues are subtracted. The check never blocks a run for its
 * own reasons (no browser available, a build that validation already reported): it is then skipped.
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

    /** Runtime problems the staged change introduced; empty when there are none or the check could not run. */
    public List<ProjectDiagnostic> check(AgentContext context) {
        if (!properties.getSandbox().isRuntimeCheck()) return List.of();
        Probe staged = probe(context, true);
        if (staged == null || staged.issues().isEmpty()) return List.of();
        Probe live = probe(context, false);
        Set<String> existing = live == null ? Set.of()
                : live.issues().stream().map(RuntimeCheckService::fingerprint).collect(Collectors.toSet());
        return staged.issues().stream().filter(issue -> !existing.contains(fingerprint(issue)))
                .map(RuntimeCheckService::diagnostic).toList();
    }

    record Issue(String kind, String message, String source) {}

    private record Probe(List<Issue> issues) {}

    /** Builds (if the stack needs it) a copy of the staged or live project and loads it; null if skipped. */
    private Probe probe(AgentContext context, boolean staged) {
        UUID projectId = context.projectId();
        String copy = staged ? changeSets.createValidationCandidate(context, "runtime")
                : changeSets.createValidationBaseline(context, "runtime");
        long started = System.nanoTime();
        try {
            String site = site(projectId, copy);
            if (site == null) return null;
            SandboxPort.CommandResult result = sandbox.browse(projectId, site);
            if (!result.started() || !result.success()) {
                log.warn("runtime_check_skipped runId={} reason={}", context.runId(),
                        result.error().isBlank() ? result.stdout() : result.error());
                return null;
            }
            Probe probe = parse(result.stdout());
            log.info("runtime_check runId={} target={} issues={} elapsedMs={}", context.runId(), staged ? "staged" : "live",
                    probe == null ? "unreadable" : probe.issues().size(), (System.nanoTime() - started) / 1_000_000);
            return probe;
        } finally {
            try { sandbox.removeTree(projectId, copy); } catch (RuntimeException ignored) { }
        }
    }

    /** The folder to load: the readable build output, or the files themselves for a static site. */
    private String site(UUID projectId, String copy) {
        StackPack stack = stacks.detect(files, projectId, copy);
        StackPack.PreviewBuild build = stack.diagnosticBuild(files, projectId, copy);
        if (build == null) return files.regularFile(projectId, copy, "index.html") ? copy : null;
        // A broken install or build is already reported by validation; nothing to load then.
        if (dependencies.prepare(projectId, copy).failed()) return null;
        if (!sandbox.exec(projectId, copy, build.command()).success()) return null;
        return build.outputDirectories().stream().map(directory -> copy + "/" + directory)
                .filter(directory -> files.regularFile(projectId, directory, "index.html")).findFirst().orElse(null);
    }

    private Probe parse(String stdout) {
        String json = stdout.lines().filter(line -> line.startsWith("{")).reduce((first, last) -> last).orElse(null);
        if (json == null) return null;
        try {
            JsonNode report = objectMapper.readTree(json);
            if (report.hasNonNull("error")) {
                log.warn("runtime_check_probe_error reason={}", report.get("error").asText());
                return null;
            }
            List<Issue> issues = new ArrayList<>();
            for (JsonNode issue : report.path("issues")) {
                issues.add(new Issue(issue.path("kind").asText("console"), issue.path("message").asText(""),
                        issue.path("source").isNull() ? null : issue.path("source").asText(null)));
            }
            return new Probe(List.copyOf(issues));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /** Same problem, different build: asset hashes, line and column numbers and ports do not count. */
    static String fingerprint(Issue issue) {
        String message = issue.message().split("\\R", 2)[0]
                .replaceAll("-[A-Za-z0-9_-]{8}\\.(js|css|mjs)", ".$1")
                .replaceAll(":\\d+(:\\d+)?", "")
                .trim();
        return issue.kind() + "|" + message;
    }

    private static ProjectDiagnostic diagnostic(Issue issue) {
        String code = switch (issue.kind()) {
            case "exception" -> "RUNTIME_EXCEPTION";
            case "asset" -> "MISSING_ASSET";
            case "blank" -> "BLANK_PAGE";
            case "load" -> "PAGE_NOT_LOADED";
            default -> "CONSOLE_ERROR";
        };
        String message = switch (issue.kind()) {
            case "blank" -> "The page rendered nothing visible: check that the entry script runs and mounts the app into index.html";
            case "asset" -> "A file the page references is missing: " + issue.message();
            default -> issue.message() + (issue.source() == null ? "" : " (at " + issue.source() + ")");
        };
        String bounded = message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE) + "...";
        return new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, bounded, "runtime", code);
    }
}
