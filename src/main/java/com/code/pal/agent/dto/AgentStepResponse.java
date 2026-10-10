package com.code.pal.agent.dto;

import com.code.pal.agent.activity.AgentActivity;
import com.code.pal.agent.activity.AgentActivityMapper;
import com.code.pal.agent.domain.AgentStep;
import com.code.pal.agent.domain.AgentStepType;

import java.time.Instant;
import java.util.Optional;

/**
 * A replayable timeline event as the user sees it: the stored step, its data without run telemetry,
 * and what it means ({@code activity}). Tool payloads have already been redacted before persistence.
 */
public record AgentStepResponse(int seq, AgentStepType type, String content, String data, Instant createdAt, AgentActivity activity) {

    /** The step as the user sees it, or empty for an internal step (model calls, counters, …). */
    public static Optional<AgentStepResponse> visible(AgentStep step, AgentActivityMapper activities) {
        return activities.map(step.getType(), step.getContent(), step.getData())
                .map(activity -> new AgentStepResponse(step.getSeq(), step.getType(), step.getContent(),
                        activities.publicData(step.getData()), step.getCreatedAt(), activity));
    }
}
