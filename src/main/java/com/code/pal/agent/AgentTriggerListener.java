package com.code.pal.agent;

import com.code.pal.agent.dispatch.AgentDispatchService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.event.AssistantTurnRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Accept atomically before commit; dispatch after commit, with durable replay. */
@Slf4j @Component @RequiredArgsConstructor
public class AgentTriggerListener {
    private final AgentRunCreator creator;
    private final AgentRunRepository runs;
    private final AgentDispatchService dispatcher;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAssistantTurn(AssistantTurnRequested event) {
        creator.create(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterAcceptance(AssistantTurnRequested event) {
        try {
            runs.findFirstByProjectIdAndMessageIdOrderByCreatedAtAsc(event.projectId(), event.assistantMessageId())
                    .ifPresent(run -> dispatcher.dispatch(run.getId()));
        } catch (RuntimeException unavailable) {
            log.warn("agent_dispatch_deferred project={}; durable outbox will retry", event.projectId());
        }
    }
}
