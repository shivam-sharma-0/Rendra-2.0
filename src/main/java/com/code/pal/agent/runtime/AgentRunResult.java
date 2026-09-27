package com.code.pal.agent.runtime;

import com.code.pal.agent.domain.RunStatus;

import java.util.UUID;

/** Terminal result returned to the worker or another future runtime caller. */
public record AgentRunResult(
        UUID runId,
        RunStatus status,
        String response,
        String error,
        int iterations,
        int modelCalls,
        int toolCalls
) {
}
