package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.event.AssistantTurnRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Creates and enqueues an {@link AgentRun} when a user turn needs a reply. Runs {@code AFTER_COMMIT}
 * so the user message + assistant placeholder are durably persisted before the agent starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTriggerListener {

    private final AgentRunRepository runRepository;
    private final AgentJobQueue queue;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssistantTurn(AssistantTurnRequested event) {
        AgentRun run = runRepository.save(AgentRun.builder()
                .projectId(event.projectId())
                .userId(event.userId())
                .mode(event.mode())
                .messageId(event.assistantMessageId())
                .status(RunStatus.QUEUED)
                .build());
        queue.enqueue(run.getId());
        log.debug("Agent run queued id={} project={} msg={}", run.getId(), event.projectId(), event.assistantMessageId());
    }
}
