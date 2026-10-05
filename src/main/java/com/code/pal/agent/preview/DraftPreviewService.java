package com.code.pal.agent.preview;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Lets the user watch a BUILD run take shape: once the agent has stopped writing for a moment, its
 * staged changes are built into {@code .codepal/draft/<run>/<id>} and open previews switch to it. A
 * draft is never validated and never served publicly; a failed draft build keeps the previous draft.
 * The draft is removed when the run ends, and the preview goes back to the committed site.
 */
@Slf4j
@Service
public class DraftPreviewService {
    static final String DRAFTS = ProjectPaths.INTERNAL + "/draft";

    /** Told when a run's draft changes or goes away (realtime in the app). */
    public interface DraftListener {
        DraftListener NONE = new DraftListener() {
            @Override public void drafted(UUID projectId, UUID runId, String version) { }
            @Override public void ended(UUID projectId, UUID runId) { }
        };
        void drafted(UUID projectId, UUID runId, String version);
        void ended(UUID projectId, UUID runId);
    }

    /** The draft a project's preview can show right now. */
    public record ActiveDraft(UUID runId, String version) {}

    private final SiteBuildService sites;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final AgentProperties properties;
    private final DraftListener listener;
    private final Map<UUID, Draft> drafts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("draft-preview").factory());
    /** Builds run here, so one project's build never holds up another's timer. */
    private final java.util.concurrent.ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    public DraftPreviewService(SiteBuildService sites, ProjectFiles files, SandboxPort sandbox,
                               AgentProperties properties, DraftListener listener) {
        this.sites = sites;
        this.files = files;
        this.sandbox = sandbox;
        this.properties = properties;
        this.listener = listener;
    }

    /** The agent changed staged files under {@code stagedRoot}: build a draft once it has been quiet for a moment. */
    public void changed(UUID projectId, UUID runId, String stagedRoot) {
        long quiet = properties.getSandbox().getDraftPreviewQuietMs();
        if (quiet <= 0) return;
        Draft draft = drafts.computeIfAbsent(runId, id -> {
            pruneOtherRuns(projectId, runId);
            return new Draft(projectId, runId, stagedRoot);
        });
        synchronized (draft) {
            if (draft.finished) return;
            if (draft.pending != null) draft.pending.cancel(false);
            draft.pending = timer.schedule(() -> workers.submit(() -> build(draft)), quiet, TimeUnit.MILLISECONDS);
        }
    }

    /** The run is over (committed, failed or cancelled): drop its draft. */
    public void finish(UUID runId) {
        Draft draft = drafts.remove(runId);
        if (draft == null) return;
        boolean building;
        synchronized (draft) {
            draft.finished = true;
            if (draft.pending != null) draft.pending.cancel(false);
            building = draft.building;
        }
        if (!building) removeQuietly(draft.projectId, DRAFTS + "/" + runId);   // else the build removes it
        if (draft.site != null) notifyEnded(draft);
    }

    /** The folder serving this run's draft, or null when it has none (or is not this project's). */
    public String draftSite(UUID projectId, UUID runId) {
        Draft draft = drafts.get(runId);
        return draft == null || !draft.projectId.equals(projectId) ? null : draft.site;
    }

    /** The draft of the run working on this project, if one has been built. */
    public ActiveDraft activeDraft(UUID projectId) {
        for (Draft draft : drafts.values()) {
            String site = draft.site;
            if (draft.projectId.equals(projectId) && site != null) {
                return new ActiveDraft(draft.runId, site.substring(site.lastIndexOf('/') + 1));
            }
        }
        return null;
    }

    private void build(Draft draft) {
        synchronized (draft) {
            if (draft.finished) return;
            if (draft.building) {
                draft.again = true;   // the agent wrote again meanwhile: one more build after this one
                return;
            }
            draft.building = true;
            draft.pending = null;
        }
        String id = UUID.randomUUID().toString();
        String target = DRAFTS + "/" + draft.runId + "/" + id;
        long started = System.nanoTime();
        String error;
        try {
            error = sites.buildCopy(draft.projectId, draft.stagedRoot, target);
        } catch (RuntimeException crash) {
            error = crash.getMessage();
        }
        String replaced = null;
        boolean finished;
        boolean again;
        synchronized (draft) {
            draft.building = false;
            finished = draft.finished;
            again = draft.again;
            draft.again = false;
            if (!finished && error == null) {
                replaced = draft.site;
                draft.site = target;
            }
        }
        if (finished) {
            removeQuietly(draft.projectId, DRAFTS + "/" + draft.runId);
            return;
        }
        if (error == null) {
            if (replaced != null) removeQuietly(draft.projectId, replaced);
            log.info("draft_preview_built projectId={} runId={} elapsedMs={}", draft.projectId, draft.runId,
                    (System.nanoTime() - started) / 1_000_000);
            try {
                listener.drafted(draft.projectId, draft.runId, id);
            } catch (RuntimeException failure) {
                log.warn("draft_preview_not_sent runId={} reason={}", draft.runId, failure.getMessage());
            }
        } else {
            // Half-written code often does not build yet; the previous draft stays until one does.
            log.info("draft_preview_skipped projectId={} runId={} reason={}", draft.projectId, draft.runId,
                    error.length() > 300 ? error.substring(0, 300) + "..." : error);
            removeQuietly(draft.projectId, target);
        }
        if (again) changed(draft.projectId, draft.runId, draft.stagedRoot);
    }

    private void notifyEnded(Draft draft) {
        try {
            listener.ended(draft.projectId, draft.runId);
        } catch (RuntimeException failure) {
            log.warn("draft_preview_not_sent runId={} reason={}", draft.runId, failure.getMessage());
        }
    }

    /** Drafts of runs this process no longer knows (a crash or restart) are only taking space. */
    private void pruneOtherRuns(UUID projectId, UUID runId) {
        try {
            if (!files.exists(projectId, ".", DRAFTS)) return;
            for (ProjectFiles.Entry entry : files.list(projectId, DRAFTS, 1)) {
                if (!entry.directory() || entry.path().equals(runId.toString())) continue;
                if (!drafts.containsKey(parse(entry.path()))) removeQuietly(projectId, DRAFTS + "/" + entry.path());
            }
        } catch (RuntimeException error) {
            log.warn("draft_preview_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }

    private static UUID parse(String name) {
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException notARun) {
            return new UUID(0, 0);
        }
    }

    private void removeQuietly(UUID projectId, String path) {
        try {
            sandbox.removeTree(projectId, path);
        } catch (RuntimeException error) {
            log.warn("draft_preview_remove_failed projectId={} path={} reason={}", projectId, path, error.getMessage());
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        workers.shutdownNow();
    }

    private static final class Draft {
        final UUID projectId;
        final UUID runId;
        final String stagedRoot;
        ScheduledFuture<?> pending;
        boolean building;
        boolean again;
        boolean finished;
        volatile String site;

        Draft(UUID projectId, UUID runId, String stagedRoot) {
            this.projectId = projectId;
            this.runId = runId;
            this.stagedRoot = stagedRoot;
        }
    }
}
