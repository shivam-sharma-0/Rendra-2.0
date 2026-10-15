package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.dispatch.AgentDispatchService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTriggerListenerTest {

    @Test
    void redisOutageAtEnqueueKeepsTheRunForRecoveryInsteadOfFailing() {
        AgentRunCreator creator = mock(AgentRunCreator.class);
        AgentRunRepository runs = mock(AgentRunRepository.class);
        AgentDispatchService dispatcher = mock(AgentDispatchService.class);
        UUID runId = UUID.randomUUID();
        AgentRun run = AgentRun.builder().id(runId).projectId(UUID.randomUUID()).userId(7L)
                .mode(ProjectType.CHAT).status(RunStatus.QUEUED).build();
        when(creator.create(any())).thenReturn(run);
        when(runs.findFirstByProjectIdAndMessageIdOrderByCreatedAtAsc(any(), any())).thenReturn(java.util.Optional.of(run));
        doThrow(new RedisConnectionFailureException("down")).when(dispatcher).dispatch(runId);
        AgentTriggerListener listener = new AgentTriggerListener(creator, runs, dispatcher);

        assertDoesNotThrow(() -> listener.onAssistantTurn(
                new AssistantTurnRequested(run.getProjectId(), 7L, ProjectType.CHAT, "aimsg_1")));

        verify(creator).create(any());   // durable QUEUED row: AgentRunRecovery requeues it later
        assertDoesNotThrow(() -> listener.afterAcceptance(
                new AssistantTurnRequested(run.getProjectId(), 7L, ProjectType.CHAT, "aimsg_1")));
        verify(dispatcher).dispatch(runId);
    }
}
