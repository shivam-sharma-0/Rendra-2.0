package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.project.event.AssistantTurnRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Creates a durable run and publishes its queued status after the user turn has
 * committed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentTriggerListener {
    private final AgentRunCreator runCreator;
    private final AgentJobQueue queue;
    private final AgentEvents events;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAssistantTurn(AssistantTurnRequested event) {
        AgentRun run = runCreator.create(event);
        events.emit(run, AgentStepType.STATUS, "Queued", Map.of("status", RunStatus.QUEUED.name()));
        queue.enqueue(run.getId());
        log.debug("Agent run queued id={} project={} msg={}", run.getId(), event.projectId(),
                event.assistantMessageId());
    }
}
