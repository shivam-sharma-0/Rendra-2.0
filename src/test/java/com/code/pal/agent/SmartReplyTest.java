package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.vision.VisionService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.testsupport.QueuedRequestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmartReplyTest {
    @ParameterizedTest
    @ValueSource(strings = {"dashboard looks great", "website is beautiful, thanks", "app is slow", "blog"})
    void ambiguousAppMentionsUseOneReadOnlyDecisionWithoutStartingABuild(String prompt) {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        var result = fixture.run("{\"reply\":\"What would you like to improve?\",\"action\":\"ask\"}");
        assertEquals(RunStatus.DONE, result.status());
        assertEquals(ProjectType.CHAT, fixture.run.getMode());
        assertEquals(1, fixture.calls.size());
        assertTrue(fixture.calls.getFirst().tools().isEmpty());
        assertNull(MessageResponse.from(fixture.assistant).suggestedAction());
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"I need help", "I want to know who made you", "I need advice on my website"})
    void requestsForHelpAndInformationDoNotInventBuildActions(String prompt) {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.run("I can help. What would you like to understand?");
        assertEquals(ProjectType.CHAT, fixture.run.getMode());
        assertNull(MessageResponse.from(fixture.assistant).suggestedAction());
        fixture.noWrites();
    }

    @Test
    void roomCompletionWaitsForCommitAndNeverCarriesAnActorsBuildAction() {
        var fixture = new Fixture(ProjectType.CHAT, "add dark mode");
        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.run("I can add dark mode to the bakery page.");
            assertTrue(fixture.completedRoomReplies().isEmpty(), "A scoped API refresh must see committed message state");
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        assertEquals(1, fixture.completedRoomReplies().size());
        assertNull(fixture.completedRoomReplies().getFirst().suggestedAction());
        assertTrue(MessageResponse.from(fixture.assistant).suggestedAction() != null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hi", "how do you work?", "can u tell how u work?", "tum kaise kaam karte ho?",
            "how does rendra work?", "what is rendra?", "who made you?", "tum kya kya bana sakte ho?"})
    void conversationalRepliesHaveAHardThreeHundredTokenCap(String prompt) {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.properties.getLlm().setSmallTalkMaxOutputTokens(2_000);
        String reply = "Tell me what you would like to build; I can plan, create and check it with you.";
        var result = fixture.run(reply);
        assertEquals(reply, result.response(), "Product answers must not be parsed as UNCLEAR decisions");
        assertEquals(1, fixture.calls.size());
        assertEquals(300, fixture.calls.getFirst().maxTokens());
        assertTrue(fixture.calls.getFirst().tools().isEmpty());
        assertFalse(fixture.context().contains("Return ONLY one JSON object"));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"what can you build?", "why did the build fail?", "how do I deploy?"})
    void capabilityAndDiagnosticQuestionsDoNotInventAnOffer(String prompt) {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.run("I can explain that.");
        assertNull(MessageResponse.from(fixture.assistant).suggestedAction());
        fixture.noWrites();
    }

    @Test
    void unclearOfferKeepsTheUsersWordsAndBuildsWithTheAcceptedProposalAsContext() {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");
        fixture.run("{\"reply\":\"Add a contact form to the bakery page.\",\"action\":\"build\"}");
        assertEquals("hmm", fixture.assistant.getSuggestedBuildText(), "model prose is never put in the user's mouth");
        String execution = com.code.pal.message.service.ConversationProposals.executionPrompt(fixture.assistant);
        assertTrue(execution.contains("> hmm") && execution.contains("> Add a contact form to the bakery page."), execution);
        assertTrue(execution.contains("the original request wins on any conflict"), execution);
        fixture.noWrites();
    }

    @Test
    void theAcceptedProposalContextIsBounded() {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");
        fixture.run("{\"reply\":\"Add dark mode. " + "Detail. ".repeat(400) + "\",\"action\":\"build\"}");
        String execution = com.code.pal.message.service.ConversationProposals.executionPrompt(fixture.assistant);
        assertTrue(execution.contains("> Add dark mode."));
        assertTrue(execution.length() <= 2_400, "proposal context stays bounded: " + execution.length());
        fixture.noWrites();
    }

    @Test
    void compactContextKeepsSectionsAndClosingDelimiterWithinBudget() {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");
        fixture.workspace.write("AGENTS.md", "Project: Solar Bakery. " + "Long description. ".repeat(1_000));
        for (int index = 0; index < 60; index++) {
            fixture.workspace.write("src/section" + index + ".tsx", "export const Section = () => 'Bakery';");
        }
        fixture.run("{\"reply\":\"What outcome do you want?\",\"action\":\"ask\"}");
        String context = fixture.calls.getFirst().messages().stream().map(LlmClient.LlmMessage::content)
                .filter(text -> text != null && text.contains("<project_context")).findFirst().orElseThrow();
        assertTrue(context.length() <= 6_000);
        assertTrue(context.stripTrailing().endsWith("</project_context>"));
        assertTrue(context.contains("Solar Bakery"));
        assertTrue(context.contains("index.html"));
        assertTrue(context.contains("Files:"));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"hmm", "haan"})
    void unclearImageTurnsDescribeTheImageBeforeTheNoToolsDecision(String prompt) {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.request.setAttachments("uploads/mockup.png");
        fixture.vision = mock(VisionService.class);
        when(fixture.vision.describe(fixture.run.getProjectId(), List.of("uploads/mockup.png"), prompt))
                .thenReturn(List.of(new VisionService.Brief("uploads/mockup.png", "A blue bakery header with a gold logo", null)));
        fixture.run("{\"reply\":\"The image shows a bakery header.\",\"action\":\"answer\"}");
        verify(fixture.vision).describe(fixture.run.getProjectId(), List.of("uploads/mockup.png"), prompt);
        assertTrue(fixture.context().contains("A blue bakery header with a gold logo"));
        assertEquals(1, fixture.calls.size(), "One decision call follows the separately accounted vision operation");
        assertTrue(fixture.calls.getFirst().tools().isEmpty());
        assertNull(MessageResponse.from(fixture.assistant).suggestedAction());
        fixture.noWrites();
    }

    @ParameterizedTest
    @CsvSource({"answer,false", "ask,false", "build,true"})
    void unclearUsesOneContextualNoToolsCallAndDecodesReplyAction(String action, boolean offersBuild) throws Exception {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");
        String reply = action.equals("ask") ? "Which page should we improve?" : "I can help improve the bakery page.";
        var result = fixture.run("{\"reply\":\"" + reply + "\",\"action\":\"" + action + "\"}");

        assertAll(
                () -> assertEquals(RunStatus.DONE, result.status()),
                () -> assertEquals(1, fixture.calls.size()),
                () -> assertTrue(fixture.calls.getFirst().tools().isEmpty()),
                () -> assertTrue(fixture.context().contains("Solar Bakery"), "UNCLEAR must receive the actual compact project summary"),
                () -> assertTrue(fixture.context().contains("index.html")),
                () -> assertEquals(reply, result.response(), "Only the decoded human reply belongs in chat"),
                () -> assertEquals(reply, fixture.visibleDeltas(), "Decision JSON must never be streamed to the user"),
                () -> assertEquals(offersBuild, MessageResponse.from(fixture.assistant).suggestedAction() != null));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "{\"reply\":\"unfinished", "{\"reply\":\"Make a change\",\"action\":\"execute\"}",
            "{\"reply\":12,\"action\":\"build\"}", "null"})
    void malformedUnclearDecisionAsksOnceWithoutLeakingJsonOrOfferingABuild(String response) {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");

        var result = fixture.run(response);

        assertAll(
                () -> assertEquals(RunStatus.DONE, result.status()),
                () -> assertEquals(1, fixture.calls.size()),
                () -> assertEquals("What would you like to do?", result.response()),
                () -> assertEquals(result.response(), fixture.visibleDeltas()),
                () -> assertNull(MessageResponse.from(fixture.assistant).suggestedAction()));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"answer", "ask"})
    void unclearWithAnAttachmentStillUsesOneNoToolsDecisionCall(String action) throws Exception {
        var fixture = new Fixture(ProjectType.BUILD, "hmm");
        fixture.request.setAttachments("uploads/context.txt");
        fixture.workspace.write("uploads/context.txt", "Synthetic bakery design notes");
        var result = fixture.run("{\"reply\":\"What outcome do you want?\",\"action\":\"" + action + "\"}");
        assertAll(
                () -> assertEquals(RunStatus.DONE, result.status()),
                () -> assertEquals(1, fixture.calls.size()),
                () -> assertTrue(fixture.calls.getFirst().tools().isEmpty()),
                () -> assertTrue(fixture.context().contains("Solar Bakery")),
                () -> assertNull(MessageResponse.from(fixture.assistant).suggestedAction()));
        fixture.noWrites();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            BUILD | can you tell me why the build failed and fix it
            CHAT | can you tell me why the build failed and fix it
            PLAN | can you tell me why the build failed and fix it
            BUILD | explain and then fix the navbar
            CHAT | explain and then fix the navbar
            PLAN | explain and then fix the navbar
            """)
    void compoundQuestionsStayReadOnlyAndOfferThePositiveChange(ProjectType mode, String prompt) throws Exception {
        var fixture = new Fixture(mode, prompt);
        var result = fixture.run("I can explain the issue and prepare the requested fix.");
        var action = MessageResponse.from(fixture.assistant).suggestedAction();
        assertAll(
                () -> assertEquals(RunStatus.DONE, result.status()),
                () -> assertEquals(ProjectType.CHAT, fixture.run.getMode()),
                () -> assertEquals(1, fixture.calls.size()),
                () -> assertTrue(action != null, "An explicit positive fix needs the real server-owned Build this action"));
        if (action != null) assertEquals(prompt, action.message());
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"explain the navbar but don't fix it", "changes nahi krna, sirf check"})
    void negatedChangesNeverGetAnUnsolicitedBuildOffer(String prompt) throws Exception {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.run("Here is an explanation of the current page.");
        assertNull(MessageResponse.from(fixture.assistant).suggestedAction());
        fixture.noWrites();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CHAT | page blank hai
            CHAT | login button doesn't work
            CHAT | kaam nahi kar raha
            CHAT | khul nahi raha
            CHAT | error aa raha hai
            PLAN | page blank hai
            PLAN | login button doesn't work
            PLAN | kaam nahi kar raha
            PLAN | khul nahi raha
            PLAN | error aa raha hai
            """)
    void bugReportsOutsideBuildGetGroundedReadOnlyContext(ProjectType mode, String prompt) throws Exception {
        var fixture = new Fixture(mode, prompt);
        fixture.run("The current bakery page uses a static HTML entrypoint.");
        assertAll(
                () -> assertEquals(ProjectType.CHAT, fixture.run.getMode()),
                () -> assertTrue(fixture.context().contains("Solar Bakery")),
                () -> assertTrue(fixture.context().contains("index.html")),
                () -> assertNull(MessageResponse.from(fixture.assistant).suggestedAction()));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"how are you?", "kaise ho", "what's up", "good job", "my name is Shivam", "bahut badhiya"})
    void socialTurnsNeverCreateBuildOffersOrEnterTheToolPipeline(String prompt) throws Exception {
        var fixture = new Fixture(ProjectType.BUILD, prompt);
        fixture.run("Good to hear from you.");
        assertAll(
                () -> assertTrue(fixture.run.isConversational()),
                () -> assertEquals(1, fixture.calls.size()),
                () -> assertTrue(fixture.calls.getFirst().tools().isEmpty()),
                () -> assertNull(MessageResponse.from(fixture.assistant).suggestedAction()));
        fixture.noWrites();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void continuedPlanOffersKeepTheOriginalTextWithoutNestingWrappers(int depth) throws Exception {
        String original = "Add dark mode to the bakery page";
        var fixture = new Fixture(ProjectType.PLAN, "haan");
        var proposal = Message.assistantPlaceholder(fixture.run.getProjectId(), "canonical-offer", "original-user", ProjectType.PLAN);
        proposal.setSuggestedBuildText(original);
        proposal.setProposalPlanId(UUID.randomUUID());
        fixture.request.setSourceProposalId(proposal.getId());
        String wrapped = original;
        for (int index = 0; index < depth; index++) wrapped = "Build the proposed changes.\n\nOriginal request:\n> " + wrapped;
        fixture.request.setResolvedRequestText(wrapped);
        when(fixture.messages.findByIdAndProjectId(proposal.getId(), fixture.run.getProjectId())).thenReturn(Optional.of(proposal));

        fixture.run("The plan adds dark mode while preserving the bakery layout.");

        assertEquals(original, fixture.assistant.getSuggestedBuildText());
        assertEquals(original, MessageResponse.from(fixture.assistant).suggestedAction().message());
        fixture.noWrites();
    }

    private static final class Fixture {
        private final LocalProject workspace = LocalProject.create();
        private final MessageRepository messages = mock(MessageRepository.class);
        private final ChangeSetService changes = mock(ChangeSetService.class);
        private final ProjectValidationService validation = mock(ProjectValidationService.class);
        private final List<LlmClient.LlmRequest> calls = new ArrayList<>();
        private final AgentProperties properties = new AgentProperties();
        private final AgentRun run;
        private final Message request;
        private final Message assistant;
        private final ProjectService projects = mock(ProjectService.class);
        private final RealtimeService realtime = mock(RealtimeService.class);
        private VisionService vision;

        private Fixture(ProjectType mode, String prompt) {
            workspace.write("AGENTS.md", "Project summary: Solar Bakery sells bread. The page entrypoint is index.html.");
            workspace.write("index.html", "<!doctype html><html><head><title>Solar Bakery</title></head><body><h1>Solar Bakery</h1></body></html>");
            properties.getModels().setChat(List.of("scripted-model"));
            properties.getModels().setPlan(List.of("scripted-model"));
            run = AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L)
                    .messageId("smart-answer").mode(mode).status(RunStatus.QUEUED).build();
            request = Message.user(workspace.projectId, "smart-user", prompt, mode, 7L);
            QueuedRequestFixture.stubOriginal(messages, run, request);
            assistant = messages.findByIdAndProjectId(run.getMessageId(), workspace.projectId).orElseThrow();
            when(messages.findById(run.getMessageId())).thenReturn(Optional.of(assistant));
            when(messages.save(any())).thenAnswer(call -> call.getArgument(0));
            Project project = Project.builder().id(workspace.projectId).name("Solar Bakery").ownerId(7L).type(mode).build();
            when(projects.requireCanEdit(7L, workspace.projectId)).thenReturn(project);
            when(projects.requireAccessible(7L, workspace.projectId)).thenReturn(project);
        }

        private AgentRunResult run(String answer) {
            LlmClient model = (request, delta) -> {
                calls.add(request);
                delta.accept(answer);
                return new LlmClient.LlmResult(answer, null, List.of(), new LlmClient.Usage(20, 10), "stop");
            };
            var runs = mock(AgentRunRepository.class);
            when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
            var heartbeat = mock(AgentHeartbeat.class);
            when(heartbeat.start(any(), any())).thenReturn(() -> {});
            var mapper = new ObjectMapper();
            var runner = new AgentRunner(model, new ToolRegistry(List.of(new ListFilesTool())), mock(AgentToolExecutor.class),
                    mock(AgentEvents.class), properties, new ProjectContextService(properties, mapper), runs, projects,
                    messages, realtime, workspace.files, heartbeat, mock(AgentJobQueue.class), mapper,
                    changes, validation, mock(NotificationService.class), null, null, null, vision, null, null, null);
            return runner.run(run);
        }

        private String context() {
            return calls.getFirst().messages().stream().map(message -> message.content() == null ? "" : message.content())
                    .collect(Collectors.joining("\n"));
        }

        private String visibleDeltas() {
            return mockingDetails(realtime).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("sendToProject"))
                    .map(call -> (RealtimeMessage) call.getArgument(1))
                    .filter(frame -> frame.type().equals("MESSAGE_DELTA"))
                    .map(frame -> String.valueOf(((Map<?, ?>) frame.payload()).get("delta")))
                    .collect(Collectors.joining());
        }

        private List<MessageResponse> completedRoomReplies() {
            return mockingDetails(realtime).getInvocations().stream()
                    .filter(call -> call.getMethod().getName().equals("sendToProject"))
                    .map(call -> (RealtimeMessage) call.getArgument(1))
                    .filter(frame -> frame.type().equals("MESSAGE_UPSERT"))
                    .map(frame -> (MessageResponse) frame.payload())
                    .filter(message -> message.status() == MessageStatus.COMPLETE).toList();
        }

        private void noWrites() {
            verify(changes, never()).start(any(), any());
            verify(changes, never()).commit(any(), any());
            verify(validation, never()).validateAll(any());
            verify(projects, never()).advanceLifecycle(any(), any(), any());
        }
    }
}
