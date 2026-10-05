package com.code.pal.agent.preview;

import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Builds the site a project's preview and published URL serve, for stacks that need a build (Vite).
 *
 * <p>Each build runs on a copy of the project (with its dependencies linked in) and its output is copied
 * to {@code .codepal/site/<id>}; the {@code CURRENT} file then names the site to serve. Switching that one
 * small file is atomic, so visitors see either the previous complete site or the new one, never a
 * half-written build, and a failed build leaves the last good site in place. Static projects have no
 * build: their files are served as they are and any previous site is retired.
 */
@Slf4j
@Service
public class SiteBuildService {
    static final String SITE = ProjectPaths.INTERNAL + "/site";
    static final String CURRENT = "CURRENT";
    private static final String WORK = ProjectPaths.INTERNAL + "/site-build";
    private static final int ERROR_TAIL_CHARS = 1_500;

    private final StackPacks stacks;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final DependencyService dependencies;
    private final BuildVariables variables;
    private final PreviewListener listener;
    /** The latest build state per project, for viewers who open the preview between events. */
    private final Map<UUID, PreviewStatus> statuses = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired
    public SiteBuildService(StackPacks stacks, ProjectFiles files, SandboxPort sandbox, DependencyService dependencies,
                            BuildVariables variables, PreviewListener listener) {
        this.stacks = stacks;
        this.files = files;
        this.sandbox = sandbox;
        this.dependencies = dependencies;
        this.variables = variables;
        this.listener = listener;
    }

    /** For tests: no listener. */
    public SiteBuildService(StackPacks stacks, ProjectFiles files, SandboxPort sandbox, DependencyService dependencies,
                            BuildVariables variables) {
        this(stacks, files, sandbox, dependencies, variables, PreviewListener.NONE);
    }

    /** For tests: no build variables. */
    public SiteBuildService(StackPacks stacks, ProjectFiles files, SandboxPort sandbox, DependencyService dependencies) {
        this(stacks, files, sandbox, dependencies, BuildVariables.NONE);
    }

    /** New values must reach the preview: rebuild once the change is committed. */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void onVariablesChanged(BuildVariables.Changed changed) {
        buildLater(changed.projectId());
    }

    /** {@code built}: a new site is live. {@code error}: why not (the previous site, if any, stays live). */
    public record Result(boolean built, String error) {
        static Result notNeeded() { return new Result(false, null); }
        static Result failed(String error) { return new Result(false, error); }
    }

    /**
     * What the preview shows. {@code version} changes whenever the served content does, so a viewer
     * reloads exactly then; {@code error} says why the latest build failed (the previous site stays).
     */
    public record PreviewStatus(State state, String version, String error) {
        public enum State { BUILDING, READY, FAILED }
    }

    /** Told about every build, so open previews can follow it (realtime in the app, nothing in tests). */
    public interface PreviewListener {
        PreviewListener NONE = (projectId, status) -> { };
        void changed(UUID projectId, PreviewStatus status);
    }

    /** Builds the project's current files into the served site. One build per project at a time. */
    public Result build(UUID projectId) {
        ReentrantLock lock = locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            String served = version(projectId);
            publish(projectId, new PreviewStatus(PreviewStatus.State.BUILDING, served, null));
            Result result;
            try {
                result = buildLocked(projectId);
            } catch (RuntimeException crash) {
                publish(projectId, new PreviewStatus(PreviewStatus.State.FAILED, served, "Build failed: " + crash.getMessage()));
                throw crash;
            }
            publish(projectId, result.error() != null
                    ? new PreviewStatus(PreviewStatus.State.FAILED, served, result.error())
                    // A static project has no site id: its files changed, so its content is new anyway.
                    : new PreviewStatus(PreviewStatus.State.READY, result.built() ? version(projectId) : UUID.randomUUID().toString(), null));
            return result;
        } finally {
            lock.unlock();
        }
    }

    /** The latest state, or (after a restart) READY with what is served now. */
    public PreviewStatus status(UUID projectId) {
        PreviewStatus known = statuses.get(projectId);
        return known != null ? known : new PreviewStatus(PreviewStatus.State.READY, version(projectId), null);
    }

    /** Whether the project's files need a build before they can be shown (a Vite app does, a static site not). */
    public boolean needsBuild(UUID projectId) {
        try {
            return stacks.detect(files, projectId, ".").previewBuild(files, projectId, ".") != null;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private String version(UUID projectId) {
        String site = currentSite(projectId);
        return site != null ? site.substring(SITE.length() + 1) : "files";
    }

    private void publish(UUID projectId, PreviewStatus status) {
        statuses.put(projectId, status);
        try {
            listener.changed(projectId, status);
        } catch (RuntimeException error) {
            log.warn("preview_status_not_sent projectId={} reason={}", projectId, error.getMessage());
        }
    }

    /** {@link #build} without waiting, for callers that changed the files outside an agent run (rollback). */
    public void buildLater(UUID projectId) {
        background.submit(() -> {
            try {
                Result result = build(projectId);
                if (result.error() != null) log.warn("site_build_failed projectId={} reason={}", projectId, result.error());
            } catch (RuntimeException error) {
                log.error("site_build_crashed projectId={}", projectId, error);
            }
        });
    }

    /** The folder holding the site to serve, or null when the project's files are served directly. */
    public String currentSite(UUID projectId) {
        try {
            if (!files.regularFile(projectId, SITE, CURRENT)) return null;
            String id = files.readText(projectId, SITE, CURRENT).trim();
            String site = SITE + "/" + UUID.fromString(id);
            return files.regularFile(projectId, site, "index.html") ? site : null;
        } catch (RuntimeException unreadableOrInvalid) {
            return null;
        }
    }

    private Result buildLocked(UUID projectId) {
        StackPack stack = stacks.detect(files, projectId, ".");
        StackPack.PreviewBuild build = stack.previewBuild(files, projectId, ".");
        if (build == null) {
            retire(projectId);
            return Result.notNeeded();
        }
        String id = UUID.randomUUID().toString();
        long started = System.nanoTime();
        String error = compile(projectId, stack, build, ".", SITE + "/" + id);
        if (error != null) return Result.failed(error);
        String previous = currentSite(projectId);
        files.writeText(projectId, SITE, CURRENT, id);
        // The previous site stays one more build: a request that read the old CURRENT can still finish.
        java.util.Set<String> keep = new java.util.HashSet<>(List.of(id));
        if (previous != null) keep.add(previous.substring(SITE.length() + 1));
        prune(projectId, keep);
        log.info("site_build_done projectId={} stack={} elapsedMs={}", projectId, stack.id(), (System.nanoTime() - started) / 1_000_000);
        return new Result(true, null);
    }

    /**
     * Builds {@code sourceRoot} (the agent's staged changes, for a draft) into {@code target}, which then
     * holds an index.html. The served site is not touched. Returns why it failed, or null.
     */
    public String buildCopy(UUID projectId, String sourceRoot, String target) {
        StackPack stack = stacks.detect(files, projectId, sourceRoot);
        StackPack.PreviewBuild build = stack.previewBuild(files, projectId, sourceRoot);
        if (build != null) return compile(projectId, stack, build, sourceRoot, target);
        // A static site: its own files are the site, from the folder holding its entry page.
        String entry = PreviewService.entryFolder(files, projectId, sourceRoot);
        if (entry == null) return "The project has no index.html yet";
        try {
            sandbox.copyTree(projectId, entry.equals(".") ? sourceRoot : sourceRoot + "/" + entry, target);
            return null;
        } catch (RuntimeException error) {
            return "Copy failed: " + error.getMessage();
        }
    }

    /** Builds a copy of {@code source} (dependencies linked in) and copies its output to {@code target}. */
    private String compile(UUID projectId, StackPack stack, StackPack.PreviewBuild build, String source, String target) {
        String work = WORK + "/" + UUID.randomUUID();
        try {
            sandbox.copyTree(projectId, source, work);
            stack.applyBuildVariables(files, projectId, work, variables.variables(projectId));
            DependencyService.Preparation deps = dependencies.prepare(projectId, work);
            if (deps.failed()) return deps.error();
            SandboxPort.CommandResult result = sandbox.exec(projectId, work, build.command());
            if (!result.success()) {
                return "Build failed: " + (result.timedOut() ? "timed out" : tail(result.stdout() + "\n" + result.error()));
            }
            String output = build.outputDirectories().stream()
                    .filter(directory -> files.regularFile(projectId, work + "/" + directory, "index.html"))
                    .findFirst().orElse(null);
            if (output == null) return "The build produced no index.html in " + build.outputDirectories();
            sandbox.copyTree(projectId, work + "/" + output, target);
            return null;
        } catch (RuntimeException error) {
            log.warn("site_build_error projectId={} reason={}", projectId, error.getMessage());
            return "Build failed: " + error.getMessage();
        } finally {
            removeQuietly(projectId, work);
        }
    }

    /** A project that no longer needs a build is served from its files again. */
    private void retire(UUID projectId) {
        if (!files.exists(projectId, SITE, CURRENT)) return;
        files.delete(projectId, SITE, CURRENT);
        prune(projectId, java.util.Set.of());
    }

    private void prune(UUID projectId, java.util.Set<String> keep) {
        try {
            List<ProjectFiles.Entry> entries = files.list(projectId, SITE, 1);
            for (ProjectFiles.Entry entry : entries) {
                if (entry.directory() && !keep.contains(entry.path())) removeQuietly(projectId, SITE + "/" + entry.path());
            }
        } catch (RuntimeException error) {
            log.warn("site_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }

    private void removeQuietly(UUID projectId, String path) {
        try {
            sandbox.removeTree(projectId, path);
        } catch (RuntimeException error) {
            log.warn("site_remove_failed projectId={} path={} reason={}", projectId, path, error.getMessage());
        }
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= ERROR_TAIL_CHARS ? trimmed : "..." + trimmed.substring(trimmed.length() - ERROR_TAIL_CHARS);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }
}
