package com.code.pal.agent.dto;

import com.code.pal.agent.usage.AgentUsagePolicy;

import java.time.Instant;

/** The signed-in user's AI token use today. {@code quota} 0 means unlimited. */
public record DailyUsageResponse(long tokensUsed, long quota, Instant resetsAt,long reportedTokens,long unknownTokens,
                                 long reservedTokens,long unknownCalls,long capacityUsed,Long remainingTokens) {

    public static DailyUsageResponse from(AgentUsagePolicy.DailyUsage usage) {
        long capacity=Math.addExact(usage.tokensUsed(),usage.reservedTokens());
        return new DailyUsageResponse(usage.tokensUsed(), usage.quota(), usage.resetsAt(),usage.reportedTokens(),usage.unknownTokens(),
                usage.reservedTokens(),usage.unknownCalls(),capacity,usage.quota()==0?null:Math.max(0,usage.quota()-capacity));
    }
}
