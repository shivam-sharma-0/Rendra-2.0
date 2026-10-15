package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.project.event.ProjectDeleted;
import com.code.pal.project.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.project.database.GeneratedDatabaseService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Frees what a deleted project held: its runs are cancelled when the delete commits, and a sweep
 * removes the folder (code, history, dependencies, built sites) and sandbox of every deleted project
 * that still has one. The sweep waits while a run still holds the project, so files are never removed
 * under a working agent; it also catches deletes whose cleanup failed or happened while the backend
 * was down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeletedProjectCleanup {
    private static final List<RunStatus> UNFINISHED = List.of(RunStatus.QUEUED, RunStatus.RUNNING,RunStatus.COMMITTING, RunStatus.AWAITING_INPUT);

    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final AgentJobQueue queue;
    private final SandboxPort sandbox;
    private final WorkspaceStorage storage;
    private final GeneratedDatabaseService databases;
    private ProjectLeaseService leases;
    private DeletedProjectCleanupJobs jobs;
    @Autowired
    void useJobs(DeletedProjectCleanupJobs jobs){this.jobs=jobs;}
    @Autowired
    void useLeases(ProjectLeaseService leases){this.leases=leases;}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(ProjectDeleted event) {
        cancelRuns(event.projectId());
    }

    @Scheduled(initialDelayString = "${app.agent.deleted-project-sweep-ms:60000}",
               fixedDelayString = "${app.agent.deleted-project-sweep-ms:60000}")
    public void sweep() {
        List<UUID> pending = jobs==null?projects.deletedAmong(projectFolders()):jobs.pending();
        for (UUID projectId : pending) {
            if(jobs!=null)jobs.attempted(projectId);
            if (queue.isLocked(projectId)) {
                cancelRuns(projectId);   // still working: stop it, remove next time
                continue;
            }
            try {
                Runnable clean=()->{
                    if(leases!=null && leases.hasLiveLease(projectId)) {cancelRuns(projectId);return;}
                    sandbox.destroy(projectId);
                    databases.dropAll(projectId);
                    if(jobs!=null)jobs.completed(projectId);
                    log.info("deleted_project_cleaned projectId={}", projectId);
                };
                // The same filesystem/DB critical section excludes a concurrent new claimant.
                // Pending trusted IO/promotion/restore is refused by guarded, even when Redis lost its lock.
                if(leases==null)clean.run();else leases.guarded(projectId,clean);
            } catch (RuntimeException failure) {
                log.warn("deleted_project_cleanup_failed projectId={} reason={}", projectId, failure.getMessage());
            }
        }
    }

    private void cancelRuns(UUID projectId) {
        for (AgentRun run : runs.findByProjectIdAndStatusIn(projectId, UNFINISHED)) queue.requestCancel(run.getId());
    }

    /** Project folders on disk; anything not named by a project id (shared caches) is not a project. */
    private List<UUID> projectFolders() {
        Path base = storage.base();
        if (!Files.isDirectory(base)) return List.of();
        List<UUID> ids = new ArrayList<>();
        try (Stream<Path> entries = Files.list(base)) {
            entries.forEach(entry -> {
                try {
                    UUID id = UUID.fromString(entry.getFileName().toString());
                    if (id.toString().equals(entry.getFileName().toString()) && Files.isDirectory(entry)) ids.add(id);
                } catch (IllegalArgumentException notAProject) {
                    // not a project folder
                }
            });
        } catch (IOException unreadable) {
            log.warn("deleted_project_sweep_skipped reason={}", unreadable.getMessage());
        }
        return ids;
    }
}
