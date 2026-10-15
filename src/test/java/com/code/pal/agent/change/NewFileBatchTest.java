package com.code.pal.agent.change;

import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.tool.impl.CreateFilesTool;
import com.code.pal.agent.tool.impl.StartProjectTool;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NewFileBatchTest {
    private final LocalProject project = LocalProject.create();
    private final ProjectFiles files = spy(project.files);
    private final FileChangeRepository audit = mock(FileChangeRepository.class);
    private final ChangeSetService service = new ChangeSetService(audit, mock(AgentRunRepository.class),
            new ObjectMapper(), project.properties, files, project.sandbox, mock(ProjectService.class));
    private final UUID run = UUID.randomUUID();
    private final AgentContext context;

    NewFileBatchTest() {
        String root = service.start(run, project.projectId);
        var plan = new AgentPlanState();
        plan.revise(List.of("Set up", "Validate"), "Initial plan");
        context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, root, null,
                new com.code.pal.agent.context.ProjectContextTrace(), run, plan, files);
    }

    private List<ChangeSetService.NewFile> batch() {
        return List.of(new ChangeSetService.NewFile("src/a.ts", "a"), new ChangeSetService.NewFile("src/b.ts", "b"));
    }
    private void failSecondWrite(boolean afterWrite) {
        var count = new AtomicInteger();
        doAnswer(call -> {
            if (count.incrementAndGet() == 2) {
                if (afterWrite) call.callRealMethod();
                throw new IllegalStateException("Injected write failure");
            }
            return call.callRealMethod();
        }).when(files).writeText(eq(project.projectId), eq(context.root()), anyString(), anyString());
    }
    @Test void nthWriteRollsBackOnlyBatchAndCanRetryWithoutExtraRepairCharge() {
        service.create(context, "existing.txt", "keep");
        service.recordProjectValidation(context, false, List.of());
        int revision = service.view(context).revision();
        failSecondWrite(false);
        var error = assertThrows(AgentToolException.class, () -> service.createBatch(context, batch()));
        assertEquals("BATCH_WRITE_FAILED", error.code());
        assertEquals(revision, service.view(context).revision());
        assertEquals(List.of("existing.txt"), service.view(context).touchedPaths());
        assertEquals(0, service.validationProgress(context).repairAttempts());
        assertEquals("keep", files.readText(project.projectId, context.root(), "existing.txt"));
        assertFalse(files.exists(project.projectId, context.root(), "src/a.ts"));
        assertFalse(files.exists(project.projectId, context.root(), "src/b.ts"));
        verify(audit, never()).saveAllAndFlush(any());
        doCallRealMethod().when(files).writeText(eq(project.projectId), eq(context.root()), anyString(), anyString());
        var result = service.createBatch(context, batch());
        assertEquals(2, result.size());
        assertEquals(revision + 1, service.view(context).revision());
        assertEquals(1, service.validationProgress(context).repairAttempts());
        verify(audit).saveAllAndFlush(argThat(rows -> {
            int n = 0; for (var ignored : rows) n++; return n == 2;
        }));
        assertFalse(project.exists("src/a.ts"));
    }
    @Test void exceptionAfterPhysicalWriteStillCompensates() {
        failSecondWrite(true);
        assertEquals("BATCH_WRITE_FAILED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
        assertFalse(files.exists(project.projectId, context.root(), "src/a.ts"));
        assertFalse(files.exists(project.projectId, context.root(), "src/b.ts"));
        assertEquals(0, service.view(context).revision());
    }
    @Test void allPathsAndEntriesAreValidatedBeforeAnyWrite() {
        for (var entries : List.of(
                List.of(new ChangeSetService.NewFile("a", "x"), new ChangeSetService.NewFile("./a", "y")),
                List.of(new ChangeSetService.NewFile("a", "x"), new ChangeSetService.NewFile("A", "y")),
                List.of(new ChangeSetService.NewFile("A", "x"), new ChangeSetService.NewFile("a/b", "y")),
                List.of(new ChangeSetService.NewFile("a", "x"), new ChangeSetService.NewFile("a/b", "y")),
                List.of(new ChangeSetService.NewFile("a", "x"), new ChangeSetService.NewFile("../escape", "y")))) {
            assertThrows(RuntimeException.class, () -> service.createBatch(context, entries));
        }
        assertThrows(IllegalArgumentException.class, () -> new CreateFilesTool(service).execute(
                Map.of("files", List.of(Map.of("path", "a", "content", "x"), Map.of("path", "b"))), context));
        verify(files, never()).writeText(eq(project.projectId), eq(context.root()), anyString(), anyString());
        assertEquals(0, service.view(context).revision());
    }
    @Test void cleanupFailureQuarantinesStageAndDoesNotAllowCertification() {
        failSecondWrite(false);
        doThrow(new IllegalStateException("Injected cleanup failure")).when(files).delete(project.projectId, context.root(), "src/a.ts");
        assertEquals("BATCH_RECOVERY_REQUIRED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
        assertEquals("BATCH_RECOVERY_REQUIRED", assertThrows(AgentToolException.class, () -> service.validate(context)).code());
        assertThrows(AgentToolException.class, () -> service.recordProjectValidation(context, true, List.of()));
        assertThrows(AgentToolException.class, () -> service.create(context, "later.txt", "x"));
        assertFalse(project.exists("src/a.ts"));
    }
    @Test void auditFailureQuarantinesInsteadOfClaimingSafeRollback() {
        doThrow(new IllegalStateException("Unknown audit outcome")).when(audit).saveAllAndFlush(any());
        assertEquals("BATCH_RECOVERY_REQUIRED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
        assertThrows(AgentToolException.class, () -> service.validate(context));
        assertFalse(project.exists("src/a.ts"));
    }
    @Test void stopBetweenWritesOrAfterLastWriteLeavesNoFilesOrAudit() {
        for (int stopAfter : List.of(1, 2)) {
            var signal = new com.code.pal.agent.runtime.RunCancellation(run);
            var count = new AtomicInteger();
            doAnswer(call -> {
                Object result = call.callRealMethod();
                if (count.incrementAndGet() == stopAfter) signal.cancel();
                return result;
            }).when(files).writeText(eq(project.projectId), eq(context.root()), anyString(), anyString());
            try (var scope = com.code.pal.agent.runtime.RunCancellation.bind(signal)) {
                assertThrows(com.code.pal.agent.llm.LlmCancelledException.class, () -> service.createBatch(context, batch()));
            }
            assertFalse(files.exists(project.projectId, context.root(), "src/a.ts"));
            assertFalse(files.exists(project.projectId, context.root(), "src/b.ts"));
            assertEquals(0, service.view(context).revision());
        }
        verify(audit, never()).saveAllAndFlush(any());
    }
    @Test void unexpectedFileContentsArePreservedAndStageBlocked() {
        doAnswer(call -> {
            call.callRealMethod();
            project.files.writeText(project.projectId, context.root(), "src/a.ts", "unexpected edit");
            throw new IllegalStateException("Injected failure and unexpected contents");
        }).when(files).writeText(project.projectId, context.root(), "src/b.ts", "b");
        assertEquals("BATCH_RECOVERY_REQUIRED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
        assertEquals("unexpected edit", files.readText(project.projectId, context.root(), "src/a.ts"));
        assertThrows(AgentToolException.class, () -> service.validate(context));
        verify(audit, never()).saveAllAndFlush(any());
    }
    @Test void starterUsesTheSameFailureAndRetryBoundary() {
        failSecondWrite(true);
        var tool = new StartProjectTool(service);
        assertEquals("BATCH_WRITE_FAILED", assertThrows(AgentToolException.class, () -> tool.execute(Map.of("stack", "mern"), context)).code());
        assertFalse(files.exists(project.projectId, context.root(), ".gitignore"));
        assertFalse(files.exists(project.projectId, context.root(), "client/package.json"));
        doCallRealMethod().when(files).writeText(eq(project.projectId), eq(context.root()), anyString(), anyString());
        assertDoesNotThrow(() -> tool.execute(Map.of("stack", "mern"), context));
        assertEquals(1, service.view(context).revision());
    }
}
