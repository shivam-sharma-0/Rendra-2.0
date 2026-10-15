package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.usage.AgentUsagePolicy;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.LocalProject;
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
        Fixture fixture = new Fixture(model, "hello");

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
        Fixture fixture = new Fixture(model, "hello");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(1, model.models.size(), "a second model would repeat or contradict visible text");
    }

    @Test void exhaustedRoutesStopWithoutRepeatingDuplicateConfiguredModels() throws Exception {
        for (String prompt : List.of("Explain the project structure.", "hello")) {
            ScriptedModel model=new ScriptedModel(request->{throw new com.code.pal.agent.llm.LlmHttpException(401,"Every provider key was rejected");},request->{throw new com.code.pal.agent.llm.LlmHttpException(503,"Unavailable");},request->"must not repeat");
            Fixture fixture = new Fixture(model, prompt,
                    List.of("chat-a", "chat-a", "chat-b"));
            assertEquals(RunStatus.FAILED,fixture.runner.run(fixture.run).status());assertEquals(List.of("chat-a","chat-b"),model.models);fixture.cancellations.shutdown();
        }
    }

    @Test
    void smallTalkFailsAfterTheConfiguredNumberOfModels() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("down"); },
                request -> { throw new LlmException("down"); },
                request -> { throw new LlmException("down"); },
                request -> "never reached");
        Fixture fixture = new Fixture(model, "hello");

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(3, model.models.size());
    }

    @Test
    void taskModelCallsAreBudgetedAndFallBackToTheNextModel() throws Exception {
        ScriptedModel model = new ScriptedModel(
                request -> { throw new LlmException("LLM time budget exhausted for 'nvidia'"); },
                request -> "The project has no files yet.");
        Fixture fixture = new Fixture(model, "Explain the project structure.");

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
        Fixture fixture = new Fixture(model, "Explain the project structure.", List.of("chat-a", "chat-b"));

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
        when(usage.tokensUsedToday(7L)).thenReturn(997L, 997L, 999L, 1001L);   // each scripted call costs 2 tokens
        Fixture fixture = new Fixture(model, "Explain the project structure.",
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
        Fixture fixture = new Fixture(model, "hello", List.of("chat-a"), usage);

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(0, model.models.size());
    }

    /** Like the real client: waits on the run's Stop signal, then fails the call as cancelled. */
    private static String waitForStop(LlmClient.LlmRequest request) {
        com.code.pal.agent.runtime.RunCancellation cancellation = com.code.pal.agent.runtime.RunCancellation.current();
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!cancellation.isCancelled() && System.nanoTime() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new com.code.pal.agent.llm.LlmCancelledException();
    }

    private static long stopAfter(Fixture fixture, long delayMs) {
        java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        timer.schedule(() -> fixture.cancellations.cancelLocal(fixture.run.getId()), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        timer.shutdown();
        return System.nanoTime();
    }

    @Test
    void aStopDuringATaskModelCallCancelsAtOnceWithoutFallback() throws Exception {
        ScriptedModel model = new ScriptedModel(AgentRunnerLlmFallbackTest::waitForStop, request -> "never reached");
        Fixture fixture = new Fixture(model, "Explain the project structure.");

        long start = stopAfter(fixture, 200);
        AgentRunResult result = fixture.runner.run(fixture.run);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(1, model.models.size(), "a stopped call must not fall back to another model");
        assertTrue(elapsedMs < 2_000, "took " + elapsedMs + " ms");
        fixture.cancellations.shutdown();
    }

    @Test
    void aStopDuringASmallTalkReplyCancelsWithoutFallback() throws Exception {
        ScriptedModel model = new ScriptedModel(AgentRunnerLlmFallbackTest::waitForStop, request -> "never reached");
        Fixture fixture = new Fixture(model, "hello");

        stopAfter(fixture, 200);
        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.CANCELLED, result.status());
        assertEquals(1, model.models.size());
        fixture.cancellations.shutdown();
    }

    @Test
    void aRefusedReservationStopsTheRunBeforeAnyModelCall() throws Exception {
        ScriptedModel model = new ScriptedModel(request -> "never reached");
        AgentUsagePolicy usage = mock(AgentUsagePolicy.class);
        when(usage.dailyTokenQuota()).thenReturn(1_000_000L);

        LlmClient refused = (request, delta) -> { throw new com.code.pal.agent.llm.LlmQuotaExceededException(AgentUsagePolicy.QUOTA_LOW_MESSAGE); };
        Fixture fixture = new Fixture(refused, "Explain the project structure.", List.of("chat-a"), usage);

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.FAILED, result.status());
        assertEquals(AgentUsagePolicy.QUOTA_LOW_MESSAGE, result.response());
        assertEquals(0, model.models.size());
        fixture.cancellations.shutdown();
    }

    @Test
    void everyModelCallIsCappedAndTheRunnerDoesNotDuplicateGatewayAccounting() throws Exception {
        ScriptedModel model = new ScriptedModel(request -> ScriptedModel.LIST_FILES, request -> "The project has no files yet.");
        AgentUsagePolicy usage = mock(AgentUsagePolicy.class);
        when(usage.dailyTokenQuota()).thenReturn(1_000_000L);
        Fixture fixture = new Fixture(model, "Explain the project structure.", List.of("chat-a"), usage);

        AgentRunResult result = fixture.runner.run(fixture.run);

        assertEquals(RunStatus.DONE, result.status());
        assertEquals(List.of(8_192, 8_192), model.maxTokens);
        org.mockito.Mockito.verify(usage, org.mockito.Mockito.never()).reserve(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        fixture.cancellations.shutdown();
    }

    @Test
    void smallTalkUsesTheSmallOutputCap() throws Exception {
        ScriptedModel model = new ScriptedModel(request -> "Hi!");
        Fixture fixture = new Fixture(model, "hello");
        fixture.runner.run(fixture.run);
        assertEquals(List.of(300), model.maxTokens);
        fixture.cancellations.shutdown();
    }

    @Test
    void inputEstimateCountsMessagesToolCallsAndSpecs() {
        List<LlmClient.LlmMessage> messages = List.of(
                LlmClient.LlmMessage.system("x".repeat(400)),
                new LlmClient.LlmMessage("assistant", null, List.of(new LlmClient.ToolCall("c", "readFile", "{\"path\":\"a\"}")), null));
        long estimate = com.code.pal.agent.llm.TokenEstimate.input(messages, 400);
        assertTrue(estimate >= 200 && estimate <= 220, "estimate " + estimate);
    }

    /** Answers each call with the next scripted step: throw, request a listFiles call, or return final text. */
    private static final class ScriptedModel implements LlmClient {
        static final String LIST_FILES = "<call listFiles>";
        private final List<java.util.function.Function<LlmRequest, String>> steps;
        final List<String> models = new CopyOnWriteArrayList<>();
        final List<Duration> budgets = new CopyOnWriteArrayList<>();
        final List<Integer> maxTokens = new CopyOnWriteArrayList<>();

        @SafeVarargs
        ScriptedModel(java.util.function.Function<LlmRequest, String>... steps) { this.steps = List.of(steps); }

        @Override
        public LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta) {
            int index = models.size();
            models.add(request.model());
            budgets.add(request.budget());
            maxTokens.add(request.maxTokens());
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
        final AgentCancellations cancellations;

        Fixture(LlmClient model, String prompt) throws Exception {
            this(model, prompt, List.of("chat-a", "chat-b", "chat-c"));
        }

        Fixture(LlmClient model, String prompt, List<String> chatModels) throws Exception {
            this(model, prompt, chatModels, null);
        }

        Fixture(LlmClient model, String prompt, List<String> chatModels,
                AgentUsagePolicy usage) throws Exception {
            LocalProject workspace = LocalProject.create();
            UUID projectId = workspace.projectId;
            UUID runId = UUID.randomUUID();
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
        when(projects.requireCanEdit(7L, projectId)).thenReturn(project);
            MessageRepository messages = mock(MessageRepository.class);
            com.code.pal.testsupport.QueuedRequestFixture.stubOriginal(messages, run,
                    Message.user(projectId, "user-1", prompt, ProjectType.CHAT, 7L));
            when(messages.findById("assistant-1")).thenReturn(Optional.empty());
            AgentHeartbeat heartbeat = mock(AgentHeartbeat.class);
            when(heartbeat.start(runId, projectId)).thenReturn(() -> { });

            AgentJobQueue queue = mock(AgentJobQueue.class);
            cancellations = new AgentCancellations(queue);
            AgentToolExecutor tools = mock(AgentToolExecutor.class);
            when(tools.execute(eq("listFiles"), anyCollection(), anyMap(), any()))
                    .thenReturn(ToolExecutionResult.success(List.of()));
            runner = new AgentRunner(model, new ToolRegistry(List.of(new ListFilesTool())),
                    tools, mock(AgentEvents.class), properties,
                    new ProjectContextService(properties, new ObjectMapper()), runs, projects, messages,
                    mock(RealtimeService.class), workspace.files, heartbeat, queue, new ObjectMapper(),
                    mock(ChangeSetService.class), mock(ProjectValidationService.class), mock(NotificationService.class),
                    null, null, null, null, usage, null, cancellations);
        }
    }
}
