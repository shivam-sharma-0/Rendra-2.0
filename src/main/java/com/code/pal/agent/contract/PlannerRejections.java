package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.agent.llm.LlmCallContext;
import lombok.extern.slf4j.Slf4j;

/** One planning operation: a correction repeating its validator rule/location gets one alternate. */
@Slf4j
final class PlannerRejections {
    private final AgentProperties properties;
    private String previous;
    private boolean rotated;
    private int wasted;

    PlannerRejections(AgentProperties properties) { this.properties = properties; }

    ModelSelection rejected(String signature, int attempt, ModelSelection route) {
        if (attempt == 0) { previous = signature; return null; }
        if (!signature.equals(previous)) return null; // A different failure gets no extra correction slot.
        wasted++;
        log.warn("planner_correction_no_progress runId={} signature={} provider={} model={} wastedCalls={}",
                LlmCallContext.current().runId(), signature, route.provider(), route.model(), wasted);
        var policy = properties.getPlanning();
        var alternate = rotated || !policy.isCorrectionFallback() ? null : properties.freePlanningRoutes().stream()
                .limit(Math.clamp(policy.getMaxRouteAttempts(), 1, 3)).filter(candidate -> !candidate.equals(route))
                .filter(properties::available).findFirst().orElse(null);
        if (alternate == null)
            throw new IllegalArgumentException("Planner made no progress: " + signature
                    + "; one alternate exhausted or unavailable; wastedCalls=" + wasted + "; no application files written");
        rotated = true;
        return alternate;
    }
}
