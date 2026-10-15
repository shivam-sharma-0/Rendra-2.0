package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChangeSetServiceTest {
    private final LocalProject project = LocalProject.create();
    private final FileChangeRepository changes = mock(FileChangeRepository.class);
    private final com.code.pal.project.service.ProjectService permissions = mock(com.code.pal.project.service.ProjectService.class);
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final ChangeSetService service = new ChangeSetService(changes, runs,
            new ObjectMapper(), project.properties, project.files, project.sandbox, permissions);

    ChangeSetServiceTest() {
        when(runs.beginCommit(any(),any(),any())).thenReturn(1);
        when(runs.recordCodeSaved(any(),any())).thenReturn(1);
        when(runs.recordSnapshot(any(),any())).thenReturn(1);
    }
    @Test
    void revokedEditorCannotPromoteValidatedFiles() {
        project.write("src/settings.ts", "export const theme = 'light';\n");
        UUID runId = UUID.randomUUID();
        AgentContext context = context(runId, service.start(runId, project.projectId), "Patch", "Validate");
        service.patch(context, "src/settings.ts", project.files.version(project.projectId, context.root(), "src/settings.ts"), "'light'", "'dark'");
        assertTrue(service.validate(context).valid());
        service.recordProjectValidation(context, true, List.of());
        doThrow(new com.code.pal.workspace.exception.WorkspaceAccessDeniedException()).when(permissions)
                .requireCanEdit(context.userId(), project.projectId);
        assertThrows(com.code.pal.workspace.exception.WorkspaceAccessDeniedException.class,
                () -> service.commit(context, run(runId)));
        assertEquals("export const theme = 'light';\n", project.read("src/settings.ts"));
    }

    @Test
    void stagesExactPatchAndLeavesLiveFileUntouchedUntilValidatedCommit() {
        project.write("src/settings.ts", "export const theme = 'light';\n");
        when(changes.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
        UUID runId = UUID.randomUUID();
        AgentContext context = context(runId, service.start(runId, project.projectId),
                "Inspect theme architecture", "Patch settings", "Validate");

        String version = project.files.version(project.projectId, context.root(), "src/settings.ts");
        service.patch(context, "src/settings.ts", version, "'light'", "'dark'");

        assertEquals("export const theme = 'light';\n", project.read("src/settings.ts"));
        assertThrows(AgentToolException.class, () -> service.patch(context, "src/settings.ts", version, "'dark'", "'system'"));
        assertTrue(service.validate(context).valid());
        service.recordProjectValidation(context, true, List.of());
        ChangeSetService.CommitResult result = service.commit(context, run(runId));

        assertEquals("export const theme = 'dark';\n", project.read("src/settings.ts"));
        assertEquals(List.of("src/settings.ts"), result.changedPaths());
        assertEquals(ChangeSetService.SNAPSHOT_PREFIX + runId, result.snapshotRef());
        verify(changes).save(any(FileChange.class));
    }

    @Test
    void rejectsCommitWhenUserChangedLiveFile() {
        project.write("App.tsx", "old");
        UUID runId = UUID.randomUUID();
        AgentContext context = context(runId, service.start(runId, project.projectId), "Patch App", "Validate");
        service.patch(context, "App.tsx", project.files.version(project.projectId, context.root(), "App.tsx"), "old", "agent");
        service.validate(context);
        service.recordProjectValidation(context, true, List.of());
        project.write("App.tsx", "user");

        AgentToolException error = assertThrows(AgentToolException.class, () -> service.commit(context, run(runId)));
        assertEquals("STALE_PROJECT_FILE", error.code());
        assertEquals("user", project.read("App.tsx"));
    }

    @Test
    void agentToolsCannotReachInternalOrProtectedFolders() {
        UUID runId = UUID.randomUUID();
        AgentContext context = context(runId, service.start(runId, project.projectId), "Write", "Validate");

        for (String path : List.of(".codepal/history/x.txt", "src/../../escape.txt", ".git/config", "/etc/passwd", "a/.CODEPAL/b")) {
            assertThrows(IllegalArgumentException.class, () -> service.create(context, path, "x"), path);
        }
    }

    private AgentContext context(UUID runId, String root, String... steps) {
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of(steps), "Initial plan");
        return new AgentContext(project.projectId, 7L, ProjectType.BUILD, root, null,
                new com.code.pal.agent.context.ProjectContextTrace(), runId, plan, project.files);
    }

    private AgentRun run(UUID runId) {
        return AgentRun.builder().id(runId).projectId(project.projectId).userId(7L).mode(ProjectType.BUILD).build();
    }
}
