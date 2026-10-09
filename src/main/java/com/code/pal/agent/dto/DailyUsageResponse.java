package com.code.pal.agent.dto;

import com.code.pal.agent.usage.AgentUsagePolicy;

import java.time.Instant;

/** The signed-in user's AI token use today. {@code quota} 0 means unlimited. */
public record DailyUsageResponse(long tokensUsed, long quota, Instant resetsAt) {

    public static DailyUsageResponse from(AgentUsagePolicy.DailyUsage usage) {
        return new DailyUsageResponse(usage.tokensUsed(), usage.quota(), usage.resetsAt());
    }
}
