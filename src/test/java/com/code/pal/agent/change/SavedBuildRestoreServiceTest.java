package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SavedBuildRestoreServiceTest {
    private final LocalProject project = LocalProject.create();
    private final SavedBuildStore store = mock(SavedBuildStore.class);
    private final RestoreJournal journal = mock(RestoreJournal.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final UUID target = UUID.randomUUID();
    private final String root = SavedBuildStore.root(target);
    private final SavedBuildRestoreService service = service(project.files);

    SavedBuildRestoreServiceTest() {
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(leases).manualRestore(eq(project.projectId), any());
        when(runs.findByProjectIdAndStatusIn(eq(project.projectId), anyList())).thenReturn(List.of());
        when(decisions.state(project.projectId)).thenReturn(new BuildDecisions.State(
                new BuildDecisions.Choice(1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null),
                new BuildDecisions.Available(false, null), null));
        project.write("index.html", "current").write("new.txt", "new").write(".env", "keep secret")
                .write(root + "/index.html", "earlier").write(root + "/old.txt", "old");
        var manifest = SavedBuildManifest.scan(project.files, project.projectId, root);
        when(store.find(project.projectId, target)).thenReturn(new SavedBuildStore.Point(
                target, project.projectId, manifest, "BROWSER", "NONE", Instant.now()));
        baseline();
        when(journal.readState(any())).thenReturn("APPLYING");
    }

    @Test
    void restoresAnEarlierSavedAfterVersionIncludingAdditionsAndDeletionsWithAudit() {
        UUID operation = UUID.randomUUID();
        var review = service.review(1L, project.projectId, target);
        assertEquals("RESTORED", service.restore(1L, project.projectId, target, operation, review.currentDigest()).state());
        assertEquals("earlier", project.read("index.html"));
        assertEquals("old", project.read("old.txt"));
        assertFalse(project.exists("new.txt"));
        assertEquals("keep secret", project.read(".env"));
        verify(journal).prepareSaved(eq(operation), eq(target), eq(project.projectId), eq(1L), eq(review.currentDigest()), anyList());
        verify(journal).state(operation, "RESTORED");
    }

    @Test
    void refusesUncommittedEditsEvenWhenTheTargetWouldNotTouchThatFile() {
        project.write("my-notes.txt", "do not discard");
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verify(journal, never()).prepareSaved(any(), any(), any(), any(), anyString(), anyList());
        assertEquals("do not discard", project.read("my-notes.txt"));
    }

    @Test
    void refusesAStaleReviewAndAnActiveRunBeforeWriting() {
        var review = service.review(1L, project.projectId, target);
        project.write("index.html", "another saved build");
        baseline();
        assertThrows(RuntimeException.class, () -> service.restore(1L, project.projectId, target,
                UUID.randomUUID(), review.currentDigest()));
        when(runs.findByProjectIdAndStatusIn(eq(project.projectId), anyList()))
                .thenReturn(List.of(AgentRun.builder().status(RunStatus.QUEUED).build()));
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verify(journal, never()).prepareSaved(any(), any(), any(), any(), anyString(), anyList());
    }

    @Test
    void refusesUnprovedLegacyVersions() {
        when(store.find(project.projectId, target)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verifyNoInteractions(journal);
    }

    @Test
    void archiveTamperingIsRefusedBeforePrepare() {
        project.write(root + "/index.html", "tampered");
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verifyNoInteractions(journal);
    }

    @Test
    void serverBackedCodeIsNotClaimedToRollBackDatabaseState() {
        project.write("server/package.json", "{}");
        baseline();
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verifyNoInteractions(journal);
    }

    @Test
    void aPartialWriteRollsBackToTheReviewedVersion() {
        ProjectFiles files = spy(project.files);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(call -> {
            if (root.equals(call.getArgument(1)) && first.getAndSet(false)) {
                throw new IllegalStateException("fixture interrupted write");
            }
            return call.callRealMethod();
        }).when(files).copyFile(any(), anyString(), anyString(), eq("."), anyString());
        var review = service.review(1L, project.projectId, target);
        UUID operation = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> service(files).restore(
                1L, project.projectId, target, operation, review.currentDigest()));
        assertEquals("current", project.read("index.html"));
        assertEquals("new", project.read("new.txt"));
        assertFalse(project.exists("old.txt"));
        verify(journal).state(operation, "ROLLED_BACK");
    }

    @Test
    void aFileDirectoryShapeConflictIsRejectedBeforePreparingTheJournal() {
        project.files.delete(project.projectId, ".", "index.html");
        project.write("index.html/child.txt", "preserve");
        baseline();
        assertThrows(RuntimeException.class, () -> service.review(1L, project.projectId, target));
        verifyNoInteractions(journal);
        assertEquals("preserve", project.read("index.html/child.txt"));
    }

    @Test
    void aLostTerminalWriteResponseReturnsTheProvenRestoredOutcome() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("fixture lost commit response")).when(journal).state(id, "RESTORED");
        when(journal.readState(id)).thenReturn("RESTORED");
        var review = service.review(1L, project.projectId, target);
        assertEquals("RESTORED", service.restore(1L, project.projectId, target, id, review.currentDigest()).state());
        assertEquals("earlier", project.read("index.html"));
        verify(journal, never()).state(id, "ROLLED_BACK");
    }

    @Test
    void anUnattributableWriteIsKeptAndTheOperationIsQuarantined() {
        ProjectFiles files = spy(project.files);
        doAnswer(call -> {
            project.write("index.html", "unexpected concurrent edit");
            throw new IllegalStateException("fixture uncertain write");
        }).when(files).copyFile(eq(project.projectId), eq(root), anyString(), eq("."), anyString());
        var review = service.review(1L, project.projectId, target);
        UUID id = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> service(files).restore(1L, project.projectId, target, id, review.currentDigest()));
        assertEquals("unexpected concurrent edit", project.read("index.html"));
        verify(journal).state(id, "RECOVERY_REQUIRED");
        verify(journal, never()).state(id, "ROLLED_BACK");
    }

    @Test
    void permissionRevocationDuringBackupStopsBeforeAnyLiveCodeWrite() {
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 3) {
                throw new IllegalStateException("fixture owner access revoked");
            }
            return null;
        }).when(projects).requireManageable(1L, project.projectId);
        String digest = SavedBuildManifest.scan(project.files, project.projectId, ".").digest();
        UUID id = UUID.randomUUID();
        assertThrows(RuntimeException.class, () -> service.restore(1L, project.projectId, target, id, digest));
        assertEquals("current", project.read("index.html"));
        verify(journal).state(id, "ROLLED_BACK");
        verify(journal, never()).state(id, "APPLYING");
    }

    @Test
    void aRepeatedOperationReturnsItsAuditedResultWithoutWritingAgain() {
        UUID id = UUID.randomUUID();
        when(journal.read(id)).thenReturn(new RestoreJournal.Entry(id, target, project.projectId,
                ".codepal/restore/" + id, "RESTORED", 1L, "digest", List.of()));
        assertEquals("RESTORED", service.restore(1L, project.projectId, target, id, "digest").state());
        verify(leases, never()).manualRestore(any(), any());
        verify(journal, never()).state(any(), anyString());
    }

    @Test
    void aForeignOperationCannotBeReusedAndAuthorizationPrecedesFilesystemAccess() {
        UUID id = UUID.randomUUID();
        when(journal.read(id)).thenReturn(new RestoreJournal.Entry(id, target, project.projectId,
                ".codepal/restore/" + id, "RESTORED", 2L, "digest", List.of()));
        assertThrows(RuntimeException.class, () -> service.restore(1L, project.projectId, target, id, "digest"));
        doThrow(new IllegalStateException("denied")).when(projects).requireManageable(9L, project.projectId);
        assertThrows(RuntimeException.class, () -> service.review(9L, project.projectId, target));
    }

    private void baseline() {
        var current = SavedBuildManifest.scan(project.files, project.projectId, ".");
        when(store.current(project.projectId)).thenReturn(current);
    }

    private SavedBuildRestoreService service(ProjectFiles files) {
        return new SavedBuildRestoreService(leases, store, journal, files, projects, runs, decisions, project.sandbox);
    }
}
