package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.domain.Message;
import com.code.pal.testsupport.QueuedRequestFixture;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunnerOwnershipTest {
    private final AgentJobQueue queue = mock(AgentJobQueue.class);
    private final AgentHeartbeat heartbeat = mock(AgentHeartbeat.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final LlmClient model = mock(LlmClient.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final AgentRun run = AgentRun.builder().id(UUID.randomUUID()).projectId(UUID.randomUUID())
            .userId(7L).mode(ProjectType.CHAT).messageId("fixture").status(RunStatus.QUEUED).build();

    private AgentRunner runner() {
        when(runs.save(any())).thenAnswer(call -> call.getArgument(0));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        var properties = new AgentProperties();
        properties.getModels().setChat(List.of("test-model"));
        return new AgentRunner(model, new ToolRegistry(List.of()),
                mock(AgentToolExecutor.class), mock(AgentEvents.class), properties,
                mock(ProjectContextService.class), runs, projects, messages,
                mock(RealtimeService.class), mock(ProjectFiles.class), heartbeat, queue, new ObjectMapper(),
                mock(ChangeSetService.class), mock(ProjectValidationService.class), mock(NotificationService.class));
    }

    @Test
    void redisCleanupFailureCannotReplaceTheRunOutcome() {
        doThrow(new IllegalStateException("Redis unavailable")).when(queue).clearCancel(run.getId());
        doThrow(new IllegalStateException("Fixture access failure")).when(projects).requireCanEdit(7L, run.getProjectId());
        assertEquals(RunStatus.FAILED, runner().run(run).status());
    }

    @Test
    void ownershipLossDoesNotWriteCancelledByUser() {
        when(heartbeat.start(run.getId(), run.getProjectId())).thenAnswer(call -> {
            RunCancellation.current().loseOwnership();
            return (AutoCloseable) () -> {};
        });
        var result = runner().run(run);
        assertEquals(RunStatus.FAILED, result.status());
        assertNotEquals("Cancelled by user", run.getError());
        assertEquals("Worker ownership expired or changed", run.getError());
    }

    @Test
    void redisStopHintFailureUsesTheDurableStop() {
        run.setCancelRequestedAt(Instant.now());
        when(queue.isCancelRequested(run.getId())).thenThrow(new IllegalStateException("Redis unavailable"));
        assertEquals(RunStatus.CANCELLED, runner().run(run).status());
    }

    @Test
    void providerInterruptionAfterOwnershipLossIsNotAUserCancellation() {
        var user = Message.user(run.getProjectId(), "user", "hi", ProjectType.CHAT, 7L);
        QueuedRequestFixture.stubOriginal(messages, run, user);
        when(model.chatStreaming(any(), any())).thenAnswer(call -> {
            RunCancellation.current().loseOwnership();
            throw new LlmCancelledException();
        });
        assertEquals(RunStatus.FAILED, runner().run(run).status());
        assertEquals("Worker ownership expired or changed", run.getError());
    }
}
