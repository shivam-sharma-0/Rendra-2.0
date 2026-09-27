package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.snapshot.WorkspaceSnapshotService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChangeSetServiceTest {
    @Test
    void stagesExactPatchAndLeavesLiveFileUntouchedUntilValidatedCommit() throws Exception {
        Path base = Files.createTempDirectory(Path.of("target"), "changeset-");
        Path live = base.resolve(UUID.randomUUID().toString());
        Files.createDirectories(live.resolve("src"));
        Path liveFile = live.resolve("src/settings.ts");
        Files.writeString(liveFile, "export const theme = 'light';\n");

        FileChangeRepository changes = mock(FileChangeRepository.class);
        when(changes.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
        WorkspaceSnapshotService snapshots = mock(WorkspaceSnapshotService.class);
        when(snapshots.createSnapshot(any(), any())).thenReturn("refs/rendra/snapshots/test");
        AgentRunRepository runs = mock(AgentRunRepository.class);
        ChangeSetService service = new ChangeSetService(changes, snapshots, runs, new ObjectMapper(), new AgentProperties());
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        Path stage = service.start(runId, projectId, live);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Inspect theme architecture", "Patch settings", "Validate"), "Initial plan");
        AgentContext context = new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null,
                new com.code.pal.agent.context.ProjectContextTrace(), runId, plan);

        String version = FileVersions.of(stage.resolve("src/settings.ts"));
        service.patch(context, "src/settings.ts", version, "'light'", "'dark'");

        assertEquals("export const theme = 'light';\n", Files.readString(liveFile));
        assertThrows(AgentToolException.class, () -> service.patch(context, "src/settings.ts", version, "'dark'", "'system'"));
        assertTrue(service.validate(context).valid());
        service.recordProjectValidation(context, true, List.of());
        AgentRun run = AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD).build();
        ChangeSetService.CommitResult result = service.commit(context, run);

        assertEquals("export const theme = 'dark';\n", Files.readString(liveFile));
        assertEquals(List.of("src/settings.ts"), result.changedPaths());
        verify(changes).save(any(FileChange.class));
    }

    @Test
    void rejectsCommitWhenUserChangedLiveFile() throws Exception {
        Path base = Files.createTempDirectory(Path.of("target"), "changeset-stale-");
        Path live = base.resolve(UUID.randomUUID().toString());
        Files.createDirectories(live);
        Files.writeString(live.resolve("App.tsx"), "old");
        ChangeSetService service = new ChangeSetService(mock(FileChangeRepository.class),
                mock(WorkspaceSnapshotService.class), mock(AgentRunRepository.class), new ObjectMapper(), new AgentProperties());
        UUID runId = UUID.randomUUID(); UUID projectId = UUID.randomUUID();
        Path stage = service.start(runId, projectId, live);
        AgentPlanState plan = new AgentPlanState(); plan.revise(List.of("Patch App", "Validate"), "Initial");
        AgentContext context = new AgentContext(projectId, 7L, ProjectType.BUILD, stage, null,
                new com.code.pal.agent.context.ProjectContextTrace(), runId, plan);
        service.patch(context, "App.tsx", FileVersions.of(stage.resolve("App.tsx")), "old", "agent");
        service.validate(context);
        service.recordProjectValidation(context, true, List.of());
        Files.writeString(live.resolve("App.tsx"), "user");

        AgentToolException error = assertThrows(AgentToolException.class, () -> service.commit(context,
                AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.BUILD).build()));
        assertEquals("STALE_PROJECT_FILE", error.code());
        assertEquals("user", Files.readString(live.resolve("App.tsx")));
    }
}
