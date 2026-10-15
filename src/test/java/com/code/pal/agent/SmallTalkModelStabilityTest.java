package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.testsupport.QueuedRequestFixture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmallTalkModelStabilityTest {
    @Test
    void successiveGreetingsKeepTheSameModelDespiteOtherRoundRobinTraffic() throws Exception {
        var models = new ArrayList<String>();
        var properties = properties();
        LlmClient model = (request, delta) -> {
            models.add(request.model());
            return reply(delta);
        };
        for (String prompt : List.of("Hi", "thanks", "namaste")) {
            properties.modelFor(ProjectType.CHAT);
            replyTo(prompt, properties, model);
        }
        assertEquals(List.of("chat-a", "chat-a", "chat-a"), models);
    }

    @Test
    void aFailedPreferredModelFallsBackAndStaysOnTheAvailableModel() throws Exception {
        var models = new ArrayList<String>();
        var properties = properties();
        LlmClient model = (request, delta) -> {
            models.add(request.model());
            if (request.model().equals("chat-a")) throw new LlmHttpException(503, "Unavailable");
            return reply(delta);
        };
        for (String prompt : List.of("Hi", "thanks", "namaste")) {
            replyTo(prompt, properties, model);
        }
        assertEquals(List.of("chat-a", "chat-b", "chat-b", "chat-b"), models);
    }

    private static AgentProperties properties() {
        var properties = new AgentProperties();
        properties.getModels().setChat(List.of("chat-a", "chat-b", "chat-c"));
        properties.getLlm().setProviderCooldownMs(60_000);
        return properties;
    }

    private static LlmClient.LlmResult reply(Consumer<String> delta) {
        delta.accept("Hello, how can I help?");
        return new LlmClient.LlmResult("Hello, how can I help?", null, List.of(),
                new LlmClient.Usage(10, 5), "stop");
    }

    private static void replyTo(String prompt, AgentProperties properties, LlmClient model) throws Exception {
        var workspace = LocalProject.create();
        var run = AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L)
                .messageId(UUID.randomUUID().toString()).mode(ProjectType.BUILD).status(RunStatus.QUEUED).build();
        var runs = mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        var messages = mock(MessageRepository.class);
        QueuedRequestFixture.stubOriginal(messages, run,
                Message.user(workspace.projectId, UUID.randomUUID().toString(), prompt, ProjectType.BUILD, 7L));
        var rights = mock(ProjectService.class);
        var project = Project.builder().id(workspace.projectId).ownerId(7L).name("Greetings").type(ProjectType.BUILD).build();
        when(rights.requireCanEdit(7L, workspace.projectId)).thenReturn(project);
        when(rights.requireAccessible(7L, workspace.projectId)).thenReturn(project);
        var heartbeat = mock(AgentHeartbeat.class);
        when(heartbeat.start(any(), any())).thenReturn(() -> {});
        var mapper = new ObjectMapper();
        var runner = new AgentRunner(model, new ToolRegistry(List.of()), mock(AgentToolExecutor.class),
                mock(AgentEvents.class), properties, new ProjectContextService(properties, mapper), runs, rights,
                messages, mock(RealtimeService.class), workspace.files, heartbeat, mock(AgentJobQueue.class),
                mapper, mock(ChangeSetService.class), mock(ProjectValidationService.class), mock(NotificationService.class));
        assertEquals(RunStatus.DONE, runner.run(run).status());
        assertEquals(ProjectType.CHAT, run.getMode());
    }
}
