package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.IntentClassifier;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.usage.AgentUsagePolicy;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The runner hands a failing provider over to the next model and gives every model call a bounded budget. */
class AgentRunnerLlmFallbackTest {

    private static final long CALL_BUDGET_MS = 5_000;

    @Test
    void smallTalkFallsBackToTheNextChatModelWhenAProviderIsDown() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("LLM time budget exhausted for 'groq'"); },
                request -> { throw new LlmException("LLM call failed: HTTP 401"); },
                request -> "Hi! How can I help with your project?");
        Fixture fixture = new Fixture(model, AgentIntent.CONVERSATIONAL, "hello");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.DONE, result.status());
        assertEquals("Hi! How can I help with your project?", result.response());
        assertEquals(List.of("chat-a", "chat-b", "chat-c"), model.models);
        model.budgets.forEach(budget -> {
            assertNotNull(budget, "every model call needs a budget");
            assertTrue(budget.toMillis() <= CALL_BUDGET_MS, "budget " + budget);
        });
    }

    @Test
    void smallTalkIsNotRetriedOnceTextReachedTheUser() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("LLM stream interrupted after partial output (stream stalled)"); },
                request -> "should never be asked");
        Fixture fixture = new Fixture(model, AgentIntent.CONVERSATIONAL, "hello");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(1, model.models.size(), "a second model would repeat or contradict visible text");
    }

    @Test
    void smallTalkFailsAfterTheConfiguredNumberOfModels() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("down"); },
                request -> { throw new LlmException("down"); },
                request -> { throw new LlmException("down"); },
                request -> "never reached");
        Fixture fixture = new Fixture(model, AgentIntent.CONVERSATIONAL, "hello");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(3, model.models.size());
    }

    @Test
    void taskModelCallsAreBudgetedAndFallBackToTheNextModel() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("LLM time budget exhausted for 'nvidia'"); },
                request -> "The project has no files yet.");
        Fixture fixture = new Fixture(model, AgentIntent.TASK, "Explain the project structure.");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.DONE, result.status());
        assertEquals(List.of("chat-a", "chat-b"), model.models);
        model.budgets.forEach(budget -> {
            assertNotNull(budget);
            assertTrue(budget.toMillis() <= CALL_BUDGET_MS, "budget " + budget);
        });
    }

    @Test
    void aModelThatJustFailedIsSkippedForTheRestOfTheRun() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("LLM time budget exhausted for 'groq'"); },
                request -> ScriptedModel.LIST_FILES,
                request -> "The project has no files yet.");
        Fixture fixture = new Fixture(model, AgentIntent.TASK, "Explain the project structure.", List.of("chat-a", "chat-b"));

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.DONE, result.status());
        // Plain round-robin would go back to chat-a for the third call.
        assertEquals(List.of("chat-a", "chat-b", "chat-b"), model.models);
    }

    @Test
    void aRunStopsBeforeTheModelCallThatWouldExceedTheDailyQuota() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> ScriptedModel.LIST_FILES,
                request -> ScriptedModel.LIST_FILES,
                request -> ScriptedModel.LIST_FILES,
                request -> "never reached");
        AgentUsagePolicy usage = mock(AgentUsagePolicy.class);
        when(usage.dailyTokenQuota()).thenReturn(1_000L);
        when(usage.tokensUsedToday(7L)).thenReturn(997L);   // each scripted call costs 2 tokens
        Fixture fixture = new Fixture(model, AgentIntent.TASK, "Explain the project structure.",
                List.of("chat-a"), usage);

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(AgentUsagePolicy.QUOTA_MESSAGE, result.response());
        assertEquals(2, model.models.size(), "997 + 2 = 999 allows a second call; 1001 stops the third");
    }

    @Test
    void aUserAlreadyOverQuotaGetsNoModelCallAtAll() throws Exception {
        ScriptedModel model = new ScriptedModel(request -> "never reached");
        AgentUsagePolicy usage = mock(AgentUsagePolicy.class);
        when(usage.dailyTokenQuota()).thenReturn(1_000L);
        when(usage.tokensUsedToday(7L)).thenReturn(1_000L);
        Fixture fixture = new Fixture(model, AgentIntent.CONVERSATIONAL, "hello", List.of("chat-a"), usage);

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(0, model.models.size());
    }

    /** Answers each call with the next scripted step: throw, request a listFiles call, or return final text. */
    private static final class ScriptedModel implements LlmClient {
        static final String LIST_FILES = "<call listFiles>";
        private final List<java.util.function.Function<LlmRequest, String>> steps;
        final List<String> models = new CopyOnWriteArrayList<>();
        final List<Duration> budgets = new CopyOnWriteArrayList<>();

        @SafeVarargs
        ScriptedModel(java.util.function.Function<LlmRequest, String>... steps) { this.steps = List.of(steps); }

        @Override
        public LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta) {
            int index = models.size();
            models.add(request.model());
            budgets.add(request.budget());
            String answer = steps.get(index).apply(request);
            if (LIST_FILES.equals(answer)) {
                return new LlmResult("", null, List.of(new ToolCall("list-1", "listFiles", "{}")), new Usage(1, 1), "tool_calls");
            }
            onTextDelta.accept(answer);
            return new LlmResult(answer, null, List.of(), new Usage(1, 1), "stop");
        }
    }

    private static final class Fixture {
        final AgentRun run;
        final AgentRunner runner;

        Fixture(LlmClient model, AgentIntent intent, String prompt) throws Exception {
            this(model, intent, prompt, List.of("chat-a", "chat-b", "chat-c"));
        }

        Fixture(LlmClient model, AgentIntent intent, String prompt, List<String> chatModels) throws Exception {
            this(model, intent, prompt, chatModels, null);
        }

        Fixture(LlmClient model, AgentIntent intent, String prompt, List<String> chatModels,
                AgentUsagePolicy usage) throws Exception {
            UUID projectId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            Path workspace = Files.createTempDirectory(Path.of("target"), "fallback-");
            Project project = Project.builder().id(projectId).workspaceId(UUID.randomUUID()).ownerId(7L)
                    .name("Fallback").type(ProjectType.CHAT).isPublic(false)
                    .deploymentStatus(DeploymentStatus.NONE).lastActivityAt(Instant.now()).build();
            run = AgentRun.builder().id(runId).projectId(projectId).userId(7L).mode(ProjectType.CHAT)
                    .messageId("assistant-1").status(RunStatus.QUEUED).build();

            AgentProperties properties = new AgentProperties();
            properties.setMaxIterations(5);
            properties.setMaxModelCalls(5);
            properties.setMaxToolCalls(5);
            properties.getModels().setChat(chatModels);
            properties.getLlm().setCallBudgetMs(CALL_BUDGET_MS);

            AgentRunRepository runs = mock(AgentRunRepository.class);
            when(runs.save(any(AgentRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
            ProjectService projects = mock(ProjectService.class);
            when(projects.requireAccessible(7L, projectId)).thenReturn(project);
            MessageRepository messages = mock(MessageRepository.class);
            when(messages.findByProjectId(eq(projectId), any())).thenReturn(new PageImpl<>(List.of(
                    Message.user(projectId, "user-1", prompt, ProjectType.CHAT, 7L))));
            when(messages.findById("assistant-1")).thenReturn(Optional.empty());
            WorkspaceStorage storage = mock(WorkspaceStorage.class);
            when(storage.ensure(projectId)).thenReturn(workspace);
            AgentHeartbeat heartbeat = mock(AgentHeartbeat.class);
            when(heartbeat.start(runId, projectId)).thenReturn(() -> { });
            IntentClassifier classifier = mock(IntentClassifier.class);
            when(classifier.classify(any())).thenReturn(intent);

            AgentToolExecutor tools = mock(AgentToolExecutor.class);
            when(tools.execute(eq("listFiles"), anyCollection(), anyMap(), any()))
                    .thenReturn(ToolExecutionResult.success(List.of()));
            runner = new AgentRunner(model, classifier, new ToolRegistry(List.of(new ListFilesTool())),
                    tools, mock(AgentEvents.class), properties,
                    new ProjectContextService(properties, new ObjectMapper()), runs, projects, messages,
                    mock(RealtimeService.class), storage, heartbeat, mock(AgentJobQueue.class), new ObjectMapper(),
                    mock(ChangeSetService.class), mock(ProjectValidationService.class), mock(NotificationService.class),
                    null, null, new DockerProjectEnvironment(properties), usage);
        }
    }
}
