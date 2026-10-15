package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.IntentClassifier;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.impl.GetProjectMetadataTool;
import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.tool.impl.SearchCodeTool;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunnerTest {
    @Test
    void iteratesFromSearchToReadToGroundedFinalAnswer() throws Exception {
        exercise(false);
    }

    @Test
    void revokedEditorNeverReachesTheProviderOrTools() throws Exception {
        exercise(true);
    }

    @Test void restoredCountersCannotPermitAnExtraProviderCallWhenDurableAdmissionRefuses() throws Exception {
        exercise(false,true);
    }
    @Test void invalidPayloadDoesNotRepeatInEitherChatPath() throws Exception {exercise(false,false,true,false);exercise(false,false,true,true);}
    private void exercise(boolean revoked) throws Exception {exercise(revoked,false);}
    private void exercise(boolean revoked,boolean denyDurable) throws Exception {
        exercise(revoked,denyDurable,false,false);
    }
    private void exercise(boolean revoked,boolean denyDurable,boolean terminal,boolean conversational) throws Exception {
        LocalProject workspace = LocalProject.create();
        UUID projectId = workspace.projectId;
        UUID workspaceId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        Project project = Project.builder().id(projectId).workspaceId(workspaceId).ownerId(7L)
                .name("Security service").type(ProjectType.CHAT).isPublic(false)
                .deploymentStatus(DeploymentStatus.NONE).lastActivityAt(Instant.now()).build();
        AgentRun run = AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.CHAT)
                .messageId("assistant-1").status(RunStatus.QUEUED).build();
        Message userMessage = Message.user(projectId, "user-1", "Find where authentication is implemented.",
                ProjectType.CHAT, 7L);

        AgentProperties properties = new AgentProperties();
        properties.setMaxIterations(5);
        properties.setMaxModelCalls(5);
        properties.setMaxToolCalls(5);
        properties.getModels().setChat(List.of("test-model"));

        ToolRegistry registry = new ToolRegistry(List.of(new ListFilesTool(), new ReadFileTool(),
                new SearchCodeTool(), new GetProjectMetadataTool()));
        AgentToolExecutor executor = mock(AgentToolExecutor.class);
        when(executor.execute(eq("searchCode"), anyCollection(), anyMap(), any())).thenReturn(
                ToolExecutionResult.success(Map.of("matches", List.of(Map.of("path", "src/AuthService.java", "line", 12)))));
        when(executor.execute(eq("readFile"), anyCollection(), anyMap(), any())).thenReturn(
                ToolExecutionResult.success("class AuthService { void authenticate() {} }"));

        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.save(any(AgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ProjectService projects = mock(ProjectService.class);
        when(projects.requireAccessible(7L, projectId)).thenReturn(project);
        when(projects.requireCanEdit(7L, projectId)).thenReturn(project);
        if (revoked) org.mockito.Mockito.doThrow(new com.code.pal.workspace.exception.WorkspaceAccessDeniedException())
                .when(projects).requireCanEdit(7L, projectId);
        MessageRepository messages = mock(MessageRepository.class);
        com.code.pal.testsupport.QueuedRequestFixture.stubOriginal(messages, run, userMessage);
        when(messages.findById("assistant-1")).thenReturn(Optional.empty());
        AgentHeartbeat heartbeat = mock(AgentHeartbeat.class);
        when(heartbeat.start(runId, projectId)).thenReturn(() -> { });
        AgentJobQueue queue = mock(AgentJobQueue.class);
        when(queue.isCancelRequested(runId)).thenReturn(false);

        IntentClassifier intentClassifier = mock(IntentClassifier.class);
        when(intentClassifier.classify(any())).thenReturn(conversational?AgentIntent.CONVERSATIONAL:AgentIntent.TASK);
        var model=new SearchReadAnswerModel();
        LlmClient selectedModel=terminal?(request,delta)->{model.calls.incrementAndGet();throw new com.code.pal.agent.llm.LlmHttpException(400,"Fixture invalid request");}:model;
        AgentRunner runner = new AgentRunner(selectedModel, intentClassifier, registry, executor,
                mock(AgentEvents.class), properties, new ProjectContextService(properties, new ObjectMapper()),
                runs, projects, messages, mock(RealtimeService.class),
                workspace.files, heartbeat, queue, new ObjectMapper(), mock(ChangeSetService.class),
                mock(ProjectValidationService.class), mock(NotificationService.class));

        if(denyDurable) {
            var progress=mock(com.code.pal.agent.change.SequentialCheckpoints.class);
            when(progress.configure(any(),any())).thenReturn(new com.code.pal.agent.change.SequentialCheckpoints.Progress(4,4,2,1,60_000));
            runner.useCheckpoints(progress);
        }
        AgentRunResult result = runner.run(run);
        if(terminal){assertEquals(RunStatus.FAILED,result.status());assertEquals(1,model.calls.get());assertEquals(com.code.pal.agent.domain.AgentFailureReason.PROVIDER_REQUEST_REJECTED,run.getFailureReason());org.mockito.Mockito.verifyNoInteractions(executor);return;}
        if(denyDurable) {
            assertEquals(RunStatus.FAILED,result.status());assertEquals(4,result.modelCalls());assertEquals(0,model.calls.get());
            org.mockito.Mockito.verifyNoInteractions(executor);return;
        }

        if (revoked) {
            assertEquals(RunStatus.FAILED, result.status());
            assertEquals(0, result.modelCalls());
            org.mockito.Mockito.verifyNoInteractions(executor, intentClassifier);
            return;
        }

        assertEquals(RunStatus.DONE, result.status());
        assertEquals(3, result.modelCalls());
        assertEquals(2, result.toolCalls());
        assertTrue(result.response().contains("AuthService.java"));
        verify(executor).execute(eq("searchCode"), anyCollection(), anyMap(), any());
        verify(executor).execute(eq("readFile"), anyCollection(), anyMap(), any());
    }

    private static final class SearchReadAnswerModel implements LlmClient {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public LlmResult chatStreaming(LlmRequest request, java.util.function.Consumer<String> onTextDelta) {
            return switch (calls.incrementAndGet()) {
                case 1 -> new LlmResult("", null, List.of(new ToolCall("search-1", "searchCode",
                        "{\"query\":\"authentication\"}")), new Usage(1, 1), "tool_calls");
                case 2 -> {
                    assertTrue(request.messages().getLast().content().contains("\"success\":true"));
                    yield new LlmResult("", null, List.of(new ToolCall("read-1", "readFile",
                            "{\"path\":\"src/AuthService.java\"}")), new Usage(1, 1), "tool_calls");
                }
                default -> {
                    String answer = "Authentication is implemented in src/AuthService.java.";
                    onTextDelta.accept(answer);
                    yield new LlmResult(answer, null, List.of(), new Usage(1, 1), "stop");
                }
            };
        }
    }
}
