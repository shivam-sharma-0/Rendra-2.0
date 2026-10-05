package com.code.pal.agent.change;

import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.LocalProjectEnvironment;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Commit must never lose untouched files, and a crash mid-commit must be recoverable. */
class ChangeSetCommitSafetyTest {

    private final FileChangeRepository changes = mock(FileChangeRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final LocalProject project = LocalProject.create();
    private final HostProjectFiles files = spy(project.files);
    private final LocalProjectEnvironment sandbox = spy(project.sandbox);
    private final UUID projectId = project.projectId;

    ChangeSetCommitSafetyTest() {
        when(changes.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void restorePointIsSavedBeforeTheFirstLiveWrite() {
        project.write("a.txt", "original");
        ChangeSetService service = service(project.properties);
        UUID runId = UUID.randomUUID();
        AgentContext context = context(service, runId);
        patch(service, context, "a.txt");
        service.recordProjectValidation(context, true, List.of());
        AgentRun run = run(runId);

        service.commit(context, run);

        InOrder order = inOrder(sandbox, runs, files);
        order.verify(sandbox).copyTree(projectId, ".", ".codepal/history/" + runId + "/original");
        order.verify(runs).save(run);                                                        // restore point recorded...
        order.verify(files).copyFile(projectId, context.root(), "a.txt", ".", "a.txt");      // ...before any live write
        assertEquals("archive:" + runId, run.getSnapshotRef());
        assertEquals(".codepal/history/" + runId + "/original", ChangeSetService.archiveFor(run.getSnapshotRef()));
        assertEquals("agent", project.read("a.txt"));
    }

    @Test
    void archiveFailureWritesNothingLive() {
        project.write("a.txt", "original a").write("b.txt", "original b");
        ChangeSetService service = service(project.properties);
        UUID runId = UUID.randomUUID();
        doThrow(new IllegalStateException("disk full")).when(sandbox)
                .copyTree(projectId, ".", ".codepal/history/" + runId + "/original");
        AgentContext context = context(service, runId);
        patch(service, context, "a.txt");
        patch(service, context, "b.txt");
        service.recordProjectValidation(context, true, List.of());
        AgentRun run = run(runId);

        assertThrows(IllegalStateException.class, () -> service.commit(context, run));

        verify(files, never()).copyFile(any(), anyString(), anyString(), eq("."), anyString());
        verify(files, never()).delete(any(), eq("."), anyString());
        assertNull(run.getSnapshotRef(), "no restore point for a commit that never started");
        assertEquals("original a", project.read("a.txt"));
        assertEquals("original b", project.read("b.txt"));
    }

    @Test
    void aFailedApplyRestoresEveryTouchedFileAndLeavesTheRestAlone() {
        project.write("a.txt", "original a").write("b.txt", "original b").write("untouched.txt", "keep");
        ChangeSetService service = service(project.properties);
        UUID runId = UUID.randomUUID();
        AgentContext context = context(service, runId);
        patch(service, context, "a.txt");
        patch(service, context, "b.txt");
        service.create(context, "new.txt", "created");
        service.recordProjectValidation(context, true, List.of());
        // a.txt is applied first (sorted), then b.txt fails mid-commit.
        doThrow(new IllegalStateException("write failed")).when(files)
                .copyFile(projectId, context.root(), "b.txt", ".", "b.txt");

        assertThrows(IllegalStateException.class, () -> service.commit(context, run(runId)));

        assertEquals("original a", project.read("a.txt"));
        assertEquals("original b", project.read("b.txt"));
        assertEquals("keep", project.read("untouched.txt"));
        assertFalse(project.exists("new.txt"), "a file the run created is removed again");
    }

    @Test
    void aCommitOverTheQuotaIsRolledBack() {
        AgentProperties tight = project.properties;
        tight.getSandbox().setMaxProjectBytes(64);
        project.write("a.txt", "small");
        ChangeSetService service = service(tight);
        UUID runId = UUID.randomUUID();
        AgentContext context = context(service, runId);
        service.create(context, "big.txt", "x".repeat(200));
        service.recordProjectValidation(context, true, List.of());

        assertThrows(IllegalStateException.class, () -> service.commit(context, run(runId)));

        assertFalse(project.exists("big.txt"));
        assertEquals("small", project.read("a.txt"));
    }

    @Test
    void onlyTheLatestArchiveIsKeptAndStagingIsRemoved() {
        project.write("a.txt", "v0");
        ChangeSetService service = service(project.properties);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (UUID runId : List.of(first, second)) {
            AgentContext context = context(service, runId);
            patch(service, context, "a.txt");
            service.recordProjectValidation(context, true, List.of());
            service.commit(context, run(runId));
            project.write("a.txt", "original again");
        }

        assertFalse(project.exists(".codepal/history/" + first), "older archives are pruned");
        assertTrue(project.exists(".codepal/history/" + second + "/original/a.txt"));
        assertFalse(project.exists(".codepal/changesets/" + first));
        assertFalse(project.exists(".codepal/changesets/" + second));
    }

    @Test
    void abandonedStagingIsRemovedButALiveRunIsLeftAlone() {
        project.write("a.txt", "original");
        UUID crashedRun = UUID.randomUUID();
        service(project.properties).start(crashedRun, projectId);          // the process that "died"
        assertTrue(project.exists(".codepal/changesets/" + crashedRun + "/workspace/a.txt"));

        ChangeSetService restarted = service(project.properties);           // fresh process, empty memory
        UUID liveRun = UUID.randomUUID();
        restarted.start(liveRun, projectId);
        restarted.cleanupAbandoned(projectId, crashedRun);
        restarted.cleanupAbandoned(projectId, liveRun);

        assertFalse(project.exists(".codepal/changesets/" + crashedRun));
        assertTrue(project.exists(".codepal/changesets/" + liveRun + "/workspace/a.txt"),
                "a run still active in this process must keep its staging area");
    }

    @Test
    void stagingNeverCopiesInternalOrSecretFolders() {
        project.write("a.txt", "x").write(".secrets/key", "secret").write(".codepal/builds/b.json", "{}");
        UUID runId = UUID.randomUUID();
        String stage = service(project.properties).start(runId, projectId);

        assertTrue(project.exists(stage + "/a.txt"));
        assertFalse(project.exists(stage + "/.secrets"));
        assertFalse(project.exists(stage + "/.codepal"));
    }

    @Test
    void unknownSnapshotReferencesHaveNoArchive() {
        assertNull(ChangeSetService.archiveFor("refs/rendra/snapshots/x"));
        assertNull(ChangeSetService.archiveFor("archive:../../etc"));
        assertNull(ChangeSetService.archiveFor(null));
        UUID runId = UUID.randomUUID();
        assertEquals(".codepal/history/" + runId + "/original", ChangeSetService.archiveFor("docker:" + runId));
    }

    /* ── helpers ─────────────────────────────────────────────────────────────── */

    private ChangeSetService service(AgentProperties properties) {
        return new ChangeSetService(changes, runs, new ObjectMapper(), properties, files, sandbox);
    }

    private AgentContext context(ChangeSetService service, UUID runId) {
        String stage = service.start(runId, projectId);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Change files", "Validate"), "Initial");
        return new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null, new ProjectContextTrace(), runId, plan, files);
    }

    private void patch(ChangeSetService service, AgentContext context, String path) {
        String current = files.readText(projectId, context.root(), path);
        service.patch(context, path, files.version(projectId, context.root(), path), current, "agent");
    }

    private AgentRun run(UUID runId) {
        return AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD).build();
    }
}
