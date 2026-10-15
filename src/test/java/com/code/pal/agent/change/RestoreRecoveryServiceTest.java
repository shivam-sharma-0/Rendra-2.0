package com.code.pal.agent.change;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestoreRecoveryServiceTest {
    private final LocalProject project = LocalProject.create();
    private final UUID run = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final String backup = ".codepal/restore/" + id;
    private final String archive = ".codepal/history/" + run + "/original";
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final RestoreJournal journal = mock(RestoreJournal.class);
    private final PromotionJournal promotions = mock(PromotionJournal.class);
    private final SandboxPort sandbox = mock(SandboxPort.class);
    private final SiteBuildService sites = mock(SiteBuildService.class);
    private final RestoreRecoveryService recovery = new RestoreRecoveryService(
            leases, journal, promotions, project.files, sandbox, sites);

    @BeforeEach
    void prepareEvidence() {
        project.write("a.txt", "new a").write("b.txt", "new b").write("unrelated.txt", "keep");
        project.write(backup + "/a.txt", "new a").write(backup + "/b.txt", "new b");
        project.write(archive + "/a.txt", "old a").write(archive + "/b.txt", "old b");
        var files = List.of(proof("a.txt"), proof("b.txt"));
        when(promotions.read(run)).thenReturn(new PromotionJournal.Entry(
                run, project.projectId, null, 1, 1, files, "SAVED", Instant.now()));
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).recoveringRestore(any(), any());
        when(sandbox.reconcilePendingOperations(project.projectId)).thenReturn(true);
    }

    private PromotionJournal.FileState proof(String path) {
        return new PromotionJournal.FileState(path, project.files.version(project.projectId, archive, path),
                project.files.version(project.projectId, ".", path));
    }

    private void pending(String state) {
        var entry = new RestoreJournal.Entry(id, run, project.projectId, backup, state);
        when(journal.pending()).thenReturn(List.of(entry));
        when(journal.read(id)).thenReturn(entry);
    }

    private void sweep() {
        recovery.recoverPending(System.nanoTime() + 5_000_000_000L);
        assertEquals("keep", project.read("unrelated.txt"));
    }

    @Test
    void interruptedPreparingNeverChangesLiveFilesAndRemovesItsBackup() {
        pending("PREPARING");
        project.write("a.txt", "external edit");
        sweep();
        assertEquals("external edit", project.read("a.txt"));
        verify(journal).state(id, "ROLLED_BACK");
        verify(sandbox).removeTree(project.projectId, backup);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLYING", "RECOVERY_REQUIRED"})
    void completesAHashProvenFullyAppliedRestore(String state) {
        pending(state);
        project.write("a.txt", "old a").write("b.txt", "old b");
        sweep();
        verify(journal).state(id, "RESTORED");
        verify(sites).buildLater(project.projectId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLYING", "RECOVERY_REQUIRED"})
    void finalizesAnAlreadyCompensatedRestore(String state) {
        pending(state);
        sweep();
        verify(journal).state(id, "ROLLED_BACK");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLYING", "RECOVERY_REQUIRED"})
    void rollsBackMixedFilesFromVerifiedBackup(String state) {
        pending(state);
        project.write("a.txt", "old a");
        sweep();
        assertEquals("new a", project.read("a.txt"));
        verify(journal).state(id, "ROLLED_BACK");
    }

    @Test
    void unknownEditIsRetainedWhileOtherProvenFilesAreCompensated() {
        pending("APPLYING");
        project.write("a.txt", "old a").write("b.txt", "external edit");
        sweep();
        assertEquals("new a", project.read("a.txt"));
        assertEquals("external edit", project.read("b.txt"));
        verify(journal).state(id, "RECOVERY_REQUIRED");
        verify(sandbox, never()).removeTree(any(), any());
    }

    @Test
    void corruptBackupKeepsTheProjectQuarantined() {
        pending("APPLYING");
        project.write("a.txt", "old a").write(backup + "/a.txt", "corrupt");
        sweep();
        assertEquals("old a", project.read("a.txt"));
        verify(journal).state(id, "RECOVERY_REQUIRED");
        verify(sandbox, never()).removeTree(any(), any());
    }

    @Test
    void uncertainFinalizationRetainsTheBackupForTheNextSweep() {
        pending("APPLYING");
        doThrow(new IllegalStateException("DB unavailable")).when(journal).state(id, "ROLLED_BACK");
        sweep();
        verify(sandbox, never()).removeTree(any(), any());
    }

    @Test
    void unprovenProcessCleanupPreventsRecoveryWrites() {
        pending("PREPARING");
        when(sandbox.reconcilePendingOperations(project.projectId)).thenReturn(false);
        sweep();
        verify(journal, never()).state(any(), any());
    }

    @Test
    void mixedRestoreRecreatesAFileDeletedByTheRestore() {
        pending("APPLYING");
        var created = new PromotionJournal.FileState("a.txt", FileVersions.ABSENT,
                project.files.version(project.projectId, ".", "a.txt"));
        when(promotions.read(run)).thenReturn(new PromotionJournal.Entry(run, project.projectId, null,
                1, 1, List.of(created, proof("b.txt")), "SAVED", Instant.now()));
        project.files.delete(project.projectId, ".", "a.txt");
        sweep();
        assertEquals("new a", project.read("a.txt"));
        verify(journal).state(id, "ROLLED_BACK");
    }

    @Test
    void mixedRestoreRemovesAFileRecreatedByTheRestore() {
        pending("APPLYING");
        var removed = new PromotionJournal.FileState("a.txt",
                project.files.version(project.projectId, archive, "a.txt"), FileVersions.ABSENT);
        when(promotions.read(run)).thenReturn(new PromotionJournal.Entry(run, project.projectId, null,
                1, 1, List.of(removed, proof("b.txt")), "SAVED", Instant.now()));
        project.write("a.txt", "old a");
        sweep();
        assertEquals(FileVersions.ABSENT, project.files.version(project.projectId, ".", "a.txt"));
        verify(journal).state(id, "ROLLED_BACK");
    }

    @Test
    void invalidBackupRootCannotDeleteOtherProjectState() {
        var entry = new RestoreJournal.Entry(id, run, project.projectId, ".codepal/history", "PREPARING");
        when(journal.pending()).thenReturn(List.of(entry));
        when(journal.read(id)).thenReturn(entry);
        sweep();
        verify(journal, never()).state(any(), any());
        verify(sandbox, never()).removeTree(any(), any());
    }
}
