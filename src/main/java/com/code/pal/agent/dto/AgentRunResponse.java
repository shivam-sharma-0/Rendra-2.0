package com.code.pal.agent.dto;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.project.domain.ProjectType;

import java.time.Instant;
import java.util.UUID;

/** Safe public status of one agent run. */
public record AgentRunResponse(UUID id, UUID projectId, String messageId, ProjectType mode, RunStatus status,
                               int promptTokens, int completionTokens, boolean rollbackAvailable, boolean conversational,
                               Instant createdAt, Instant updatedAt, Instant respondingAt) {
    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(run.getId(), run.getProjectId(), run.getMessageId(), run.getMode(), run.getStatus(),
                run.getPromptTokens(), run.getCompletionTokens(), run.getSnapshotRef() != null, run.isConversational(),
                run.getCreatedAt(), run.getUpdatedAt(), run.getRespondingAt());
    }
}
