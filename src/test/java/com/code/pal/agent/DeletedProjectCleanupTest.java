package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.event.ProjectDeleted;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeletedProjectCleanupTest {
    private final LocalProject deleted = LocalProject.create();
    private final UUID kept = UUID.randomUUID();
    private final ProjectService projects = mock(ProjectService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final AgentJobQueue queue = mock(AgentJobQueue.class);
    private final DeletedProjectCleanup cleanup = new DeletedProjectCleanup(projects, runs, queue, deleted.sandbox, deleted.storage,
            mock(com.code.pal.project.database.GeneratedDatabaseService.class));

    DeletedProjectCleanupTest() throws Exception {
        deleted.write("src/App.tsx", "export {};\n").write(".codepal/deps/abc/node_modules/react/index.js", "x");
        deleted.storage.ensure(kept);
        Files.writeString(deleted.storage.projectDir(kept).resolve("index.html"), "<h1>kept</h1>");
        Files.createDirectories(deleted.storage.base().resolve(".npm-cache"));
        when(projects.deletedAmong(any())).thenAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.contains(deleted.projectId) ? List.of(deleted.projectId) : List.of();
        });
    }

    @Test
    void removesTheFolderOfADeletedProjectOnly() {
        cleanup.sweep();

        assertFalse(Files.exists(deleted.dir()), "deleted project's files are gone");
        assertTrue(Files.exists(deleted.storage.projectDir(kept).resolve("index.html")), "live projects are untouched");
        assertTrue(Files.isDirectory(deleted.storage.base().resolve(".npm-cache")), "shared caches are not projects");
        verify(projects).deletedAmong(argThat(ids -> ids.size() == 2));
    }

    @Test
    void waitsWhileARunStillHoldsTheProject() {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID());
        when(runs.findByProjectIdAndStatusIn(eq(deleted.projectId), any())).thenReturn(List.of(run));
        when(queue.isLocked(deleted.projectId)).thenReturn(true);

        cleanup.sweep();

        assertTrue(Files.exists(deleted.path("src/App.tsx")), "never removed under a working agent");
        verify(queue).requestCancel(run.getId());

        when(queue.isLocked(deleted.projectId)).thenReturn(false);
        cleanup.sweep();
        assertFalse(Files.exists(deleted.dir()));
    }

    @Test
    void deletingAProjectCancelsItsUnfinishedRuns() {
        AgentRun run = new AgentRun();
        run.setId(UUID.randomUUID());
        when(runs.findByProjectIdAndStatusIn(eq(deleted.projectId), any())).thenReturn(List.of(run));

        cleanup.onDeleted(new ProjectDeleted(deleted.projectId));

        verify(queue).requestCancel(run.getId());
        assertTrue(Files.exists(deleted.dir()), "files go in the sweep, after the run has stopped");
    }
    @Test void durableLeaseAndPendingRecoveryProtectFilesWhenRedisHasNoLock() {
        var leases=mock(com.code.pal.agent.lease.ProjectLeaseService.class);cleanup.useLeases(leases);
        doAnswer(c->{((Runnable)c.getArgument(1)).run();return null;}).when(leases).guarded(any(),any(Runnable.class));
        when(leases.hasLiveLease(deleted.projectId)).thenReturn(true);
        cleanup.sweep();assertTrue(Files.exists(deleted.path("src/App.tsx")));
        when(leases.hasLiveLease(deleted.projectId)).thenReturn(false);
        doThrow(new IllegalStateException("Fixture pending restore")).when(leases).guarded(any(),any(Runnable.class));
        cleanup.sweep();assertTrue(Files.exists(deleted.path("src/App.tsx")));
    }
    @Test void databaseCleanupIsRetriedAfterTheProjectFolderHasAlreadyGone() {
        var jobs=mock(DeletedProjectCleanupJobs.class);var databases=mock(com.code.pal.project.database.GeneratedDatabaseService.class);
        var worker=new DeletedProjectCleanup(projects,runs,queue,deleted.sandbox,deleted.storage,databases);worker.useJobs(jobs);
        when(jobs.pending()).thenReturn(List.of(deleted.projectId));doThrow(new IllegalStateException("Mongo unavailable")).doNothing().when(databases).dropAll(deleted.projectId);
        worker.sweep();assertFalse(Files.exists(deleted.dir()));verify(jobs,never()).completed(any());
        worker.sweep();verify(databases,times(2)).dropAll(deleted.projectId);verify(jobs).completed(deleted.projectId);
    }
}
