package com.code.pal.agent.change;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.snapshot.WorkspaceSnapshotService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Commit must never lose untouched files, and a crash mid-commit must be recoverable. */
class ChangeSetCommitSafetyTest {

    private final FileChangeRepository changes = mock(FileChangeRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final WorkspaceSnapshotService snapshots = mock(WorkspaceSnapshotService.class);

    ChangeSetCommitSafetyTest() {
        when(changes.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
        when(snapshots.createSnapshot(any(), any())).thenReturn("refs/rendra/snapshots/test");
    }

    /* ── Host mode ───────────────────────────────────────────────────────────── */

    @Test
    void hostArchiveFailureLeavesEveryLiveFileUntouched() throws Exception {
        Path live = newLiveProject();
        Files.writeString(live.resolve("a.txt"), "original a");
        Files.writeString(live.resolve("b.txt"), "original b");
        ChangeSetService service = hostService();
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AgentContext context = hostContext(service.start(runId, projectId, live), runId, projectId);
        service.patch(context, "a.txt", FileVersions.of(context.workspace().resolve("a.txt")), "original", "agent");
        service.patch(context, "b.txt", FileVersions.of(context.workspace().resolve("b.txt")), "original", "agent");
        service.recordProjectValidation(context, true, List.of());
        // A file where the archive directory should go makes archiving fail before anything is applied.
        Path archiveRoot = live.getParent().resolve(".agent-history").resolve(runId.toString());
        Files.createDirectories(archiveRoot.getParent());
        Files.writeString(archiveRoot, "blocks the archive");

        assertThrows(IllegalStateException.class, () -> service.commit(context, run(runId, context)));

        // The old code "restored" from the empty archive and deleted both files.
        assertEquals("original a", Files.readString(live.resolve("a.txt")));
        assertEquals("original b", Files.readString(live.resolve("b.txt")));
    }

    @Test
    void hostArchiveIsRemovedAfterASuccessfulCommit() throws Exception {
        Path live = newLiveProject();
        Files.writeString(live.resolve("a.txt"), "original");
        ChangeSetService service = hostService();
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AgentContext context = hostContext(service.start(runId, projectId, live), runId, projectId);
        service.patch(context, "a.txt", FileVersions.of(context.workspace().resolve("a.txt")), "original", "agent");
        service.recordProjectValidation(context, true, List.of());

        service.commit(context, run(runId, context));

        assertEquals("agent", Files.readString(live.resolve("a.txt")));
        assertFalse(Files.exists(live.getParent().resolve(".agent-history").resolve(runId.toString())),
                "host rollback uses the Git snapshot; the per-run archive must not accumulate");
    }

    @Test
    void abandonedHostStagingIsRemovedButALiveRunIsLeftAlone() throws Exception {
        Path live = newLiveProject();
        Files.writeString(live.resolve("a.txt"), "original");
        UUID projectId = UUID.randomUUID();
        UUID crashedRun = UUID.randomUUID();
        hostService().start(crashedRun, projectId, live);          // the process that "died"
        Path crashedStaging = live.getParent().resolve(".agent-changesets").resolve(crashedRun.toString());
        assertTrue(Files.isDirectory(crashedStaging));

        ChangeSetService restarted = hostService();                 // fresh process, empty memory
        UUID liveRun = UUID.randomUUID();
        restarted.start(liveRun, projectId, live);
        restarted.cleanupAbandoned(projectId, crashedRun, live);
        restarted.cleanupAbandoned(projectId, liveRun, live);

        assertFalse(Files.exists(crashedStaging));
        assertTrue(Files.isDirectory(live.getParent().resolve(".agent-changesets").resolve(liveRun.toString())),
                "a run still active in this process must keep its staging area");
    }

    /* ── Docker mode ─────────────────────────────────────────────────────────── */

    @Test
    void sandboxRestorePointIsSavedBeforeTheFirstLiveWriteAndOldHistoryIsPruned() {
        DockerProjectEnvironment docker = dockerWithNewFile();
        ChangeSetService service = sandboxService(docker);
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        AgentContext context = sandboxContext(service, docker, runId, projectId);
        service.create(context, "index.html", "<h1>hi</h1>");
        service.recordProjectValidation(context, true, List.of());
        AgentRun run = run(runId, context);

        service.commit(context, run);

        String stage = ".codepal/changesets/" + runId + "/workspace";
        InOrder order = inOrder(docker, runs);
        order.verify(docker).copyTree(projectId, ".", ".codepal/history/" + runId + "/original");
        order.verify(runs).save(run);                                          // restore point recorded...
        order.verify(docker).copyFile(projectId, stage, "index.html", ".", "index.html"); // ...before any live write
        assertEquals("docker:" + runId, run.getSnapshotRef());
        verify(docker).exec(eq(projectId), eq("."), argThat((List<String> command) ->
                command.get(0).equals("find") && command.contains("!") && command.contains(runId.toString())));
    }

    @Test
    void sandboxArchiveFailureWritesNothingLive() {
        DockerProjectEnvironment docker = dockerWithNewFile();
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        doThrow(new IllegalStateException("disk full")).when(docker)
                .copyTree(projectId, ".", ".codepal/history/" + runId + "/original");
        ChangeSetService service = sandboxService(docker);
        AgentContext context = sandboxContext(service, docker, runId, projectId);
        service.create(context, "index.html", "<h1>hi</h1>");
        service.recordProjectValidation(context, true, List.of());
        AgentRun run = run(runId, context);

        assertThrows(IllegalStateException.class, () -> service.commit(context, run));

        verify(docker, never()).copyFile(any(), anyString(), anyString(), eq("."), anyString());
        verify(docker, never()).delete(any(), eq("."), anyString());
        assertNull(run.getSnapshotRef(), "no restore point for a commit that never started");
    }

    /* ── helpers ─────────────────────────────────────────────────────────────── */

    private ChangeSetService hostService() {
        return new ChangeSetService(changes, snapshots, runs, new ObjectMapper(), new AgentProperties());
    }

    private ChangeSetService sandboxService(DockerProjectEnvironment docker) {
        AgentProperties properties = new AgentProperties();
        properties.getSandbox().setRequired(true);
        return new ChangeSetService(changes, snapshots, runs, new ObjectMapper(), properties, docker);
    }

    private static DockerProjectEnvironment dockerWithNewFile() {
        DockerProjectEnvironment docker = mock(DockerProjectEnvironment.class);
        when(docker.exists(any(), anyString(), anyString())).thenReturn(false);
        when(docker.version(any(), anyString(), anyString())).thenReturn("absent");
        when(docker.regularFile(any(), anyString(), eq("index.html"))).thenReturn(true);
        return docker;
    }

    private static AgentContext sandboxContext(ChangeSetService service, DockerProjectEnvironment docker,
                                               UUID runId, UUID projectId) {
        Path stage = service.start(runId, projectId);
        return new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null, new ProjectContextTrace(), runId,
                plan(), docker, ".codepal/changesets/" + runId + "/workspace");
    }

    private static AgentContext hostContext(Path stage, UUID runId, UUID projectId) {
        return new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null, new ProjectContextTrace(),
                runId, plan(), null, null);
    }

    private static AgentPlanState plan() {
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Change files", "Validate"), "Initial");
        return plan;
    }

    private static AgentRun run(UUID runId, AgentContext context) {
        return AgentRun.builder().id(runId).projectId(context.projectId()).userId(7L).mode(ProjectType.BUILD).build();
    }

    private static Path newLiveProject() throws Exception {
        Path base = Files.createTempDirectory(Path.of("target"), "commit-safety-");
        Path live = base.resolve(UUID.randomUUID().toString());
        Files.createDirectories(live);
        return live;
    }
}
