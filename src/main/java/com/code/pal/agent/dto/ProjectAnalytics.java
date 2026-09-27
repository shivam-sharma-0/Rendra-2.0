package com.code.pal.agent.dto;

import java.time.Instant;

/** Real activity aggregates for a project (from agent runs, messages and workspace files). */
public record ProjectAnalytics(
        long totalRuns,
        long completedRuns,
        long failedRuns,
        long totalMessages,
        long totalTokens,
        int fileCount,
        String deploymentStatus,
        Instant createdAt,
        Instant lastActivityAt
) {
}
