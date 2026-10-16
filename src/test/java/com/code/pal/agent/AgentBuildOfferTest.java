package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.testsupport.QueuedRequestFixture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentBuildOfferTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CHAT | add dark mode
            PLAN | add dark mode
            BUILD | a modern look
            """)
    void readonlyRepliesOfferExplicitRequestsOrConcreteUnclearProposalsWithoutExtraCalls(
            ProjectType requested, String prompt) throws Exception {
        var workspace = LocalProject.create();
        var props = new AgentProperties();
        props.getModels().setChat(List.of("test-model"));
        props.getModels().setPlan(List.of("test-model"));
        var mapper = new ObjectMapper();
        var runs = mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        var run = AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L)
                .messageId("assistant-offer").mode(requested).status(RunStatus.QUEUED).build();
        var messages = mock(MessageRepository.class);
        var original = Message.user(workspace.projectId, "user-offer", prompt, requested, 7L);
        original.setRequestedMode(requested);
        QueuedRequestFixture.stubOriginal(messages, run, original);
        var assistant = messages.findByIdAndProjectId(run.getMessageId(), workspace.projectId).orElseThrow();
        when(messages.findById(run.getMessageId())).thenReturn(Optional.of(assistant));
        when(messages.save(any())).thenAnswer(call -> call.getArgument(0));
        var rights = mock(ProjectService.class);
        var project = Project.builder().id(workspace.projectId).name("Fixture").ownerId(7L).type(requested).build();
        when(rights.requireCanEdit(7L, workspace.projectId)).thenReturn(project);
        when(rights.requireAccessible(7L, workspace.projectId)).thenReturn(project);
        var heartbeat = mock(AgentHeartbeat.class);
        when(heartbeat.start(any(), any())).thenReturn(() -> {});
        var changes = mock(ChangeSetService.class);
        var validation = mock(ProjectValidationService.class);
        var calls = new AtomicInteger();
        String proposedChange = requested == ProjectType.BUILD ? "Apply a modern layout to the existing page." : prompt;
        LlmClient model = (request, delta) -> {
            calls.incrementAndGet();
            assertFalse(request.tools().stream().anyMatch(tool -> tool.name().equals("createFile")));
            String answer = requested == ProjectType.BUILD
                    ? "{\"reply\":\"" + proposedChange + "\",\"action\":\"build\"}"
                    : "I can help with that. Use Build this when you are ready.";
            delta.accept(answer);
            return new LlmClient.LlmResult(answer, null,
                    List.of(), new LlmClient.Usage(10, 10), "stop");
        };
        var runner = new AgentRunner(model, new ToolRegistry(List.of()), mock(AgentToolExecutor.class),
                mock(AgentEvents.class), props, new ProjectContextService(props, mapper), runs, rights,
                messages, mock(RealtimeService.class), workspace.files, heartbeat, mock(AgentJobQueue.class),
                mapper, changes, validation, mock(NotificationService.class));

        assertEquals(RunStatus.DONE, runner.run(run).status());
        assertEquals(1, calls.get());
        assertEquals(requested == ProjectType.PLAN ? ProjectType.PLAN : ProjectType.CHAT, run.getMode());
        verify(changes, never()).start(any(), any());
        verify(changes, never()).commit(any(), any());
        verify(validation, never()).validateAll(any());
        var json = mapper.valueToTree(MessageResponse.from(assistant)).path("suggestedAction");
        assertTrue(json.isObject(), "Completed reply must include its backend-generated build action");
        assertEquals("Build this", json.path("label").asString());
        assertEquals("BUILD", json.path("mode").asString());
        assertEquals(prompt, json.path("message").asString(), "the offer posts the user's own words");
        assertTrue(com.code.pal.message.service.ConversationProposals.executionPrompt(assistant).contains(proposedChange),
                "the accepted proposal is context for the build");
    }
}
