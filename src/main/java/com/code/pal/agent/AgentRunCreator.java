package com.code.pal.agent;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.event.AssistantTurnRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a new run in its own transaction. Called from an AFTER_COMMIT event listener, where the
 * original request's transaction has already finished; without REQUIRES_NEW the save can attach to
 * that transaction's own completion instead of durably committing on its own, so a worker thread
 * looking the run up immediately after can find nothing.
 */
@Component
@RequiredArgsConstructor
class AgentRunCreator {

    private final AgentRunRepository runRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentRun create(AssistantTurnRequested event) {
        return runRepository.save(AgentRun.builder()
                .projectId(event.projectId()).userId(event.userId()).mode(event.mode())
                .messageId(event.assistantMessageId()).status(RunStatus.QUEUED).build());
    }
}
