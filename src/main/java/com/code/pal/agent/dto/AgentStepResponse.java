package com.code.pal.agent.dto;

import com.code.pal.agent.domain.AgentStep;
import com.code.pal.agent.domain.AgentStepType;

import java.time.Instant;

/** A replayable timeline event. Tool payloads have already been redacted before persistence. */
public record AgentStepResponse(int seq, AgentStepType type, String content, String data, Instant createdAt) {
    public static AgentStepResponse from(AgentStep step) {
        return new AgentStepResponse(step.getSeq(), step.getType(), step.getContent(), step.getData(), step.getCreatedAt());
    }
}
