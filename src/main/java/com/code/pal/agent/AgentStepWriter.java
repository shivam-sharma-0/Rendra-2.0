package com.code.pal.agent;

import com.code.pal.agent.domain.AgentStep;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.repository.AgentStepRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a single timeline step in its own transaction so {@link AgentEvents} can retry on a
 * sequence collision. Each attempt claims the next {@code seq} and flushes immediately, turning any
 * concurrent duplicate into a unique-constraint violation the caller can catch and retry.
 */
@Component
@RequiredArgsConstructor
class AgentStepWriter {

    private final AgentStepRepository steps;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int appendOnce(UUID runId, AgentStepType type, String content, String dataJson) {
        int seq = steps.findTopByRunIdOrderBySeqDesc(runId).map(step -> step.getSeq() + 1).orElse(0);
        // saveAndFlush forces the INSERT now so a (run_id, seq) collision surfaces inside this
        // transaction â€” not silently at commit â€” letting the caller retry with a fresh sequence.
        steps.saveAndFlush(AgentStep.builder()
                .runId(runId)
                .seq(seq)
                .type(type)
                .content(content)
                .data(dataJson)
                .build());
        return seq;
    }
}
