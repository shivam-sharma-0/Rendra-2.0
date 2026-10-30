package com.code.pal.agent.dto;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.project.domain.ProjectType;

import java.time.Instant;
import java.util.UUID;
import com.code.pal.agent.domain.AgentFailureReason;

/** Safe public status of one agent run. */
public record AgentRunResponse(UUID id, UUID projectId, String messageId, ProjectType mode, RunStatus status,
                               int promptTokens, int completionTokens, boolean rollbackAvailable, boolean conversational,
                               Instant createdAt, Instant updatedAt, Instant respondingAt,
                               Instant codeSavedAt,String previewStatus,boolean saveRecoveryRequired,boolean planAvailable,AgentFailureReason failureReason) {
    /** Offers Restore only where code restore can apply (2026-10-29 review: MERN showed a button that always failed). */
    public AgentRunResponse withRollbackAvailable(boolean restorable) {
        return new AgentRunResponse(id,projectId,messageId,mode,status,promptTokens,completionTokens,rollbackAvailable && restorable,conversational,
                createdAt,updatedAt,respondingAt,codeSavedAt,previewStatus,saveRecoveryRequired,planAvailable,failureReason);
    }
    public AgentRunResponse withPlanAvailable(boolean available) {
        return new AgentRunResponse(id,projectId,messageId,mode,status,promptTokens,completionTokens,rollbackAvailable,conversational,
                createdAt,updatedAt,respondingAt,codeSavedAt,previewStatus,saveRecoveryRequired,available,failureReason);
    }
    public static AgentRunResponse from(AgentRun run) {
        return new AgentRunResponse(run.getId(), run.getProjectId(), run.getMessageId(), run.getMode(), run.getStatus(),
                run.getPromptTokens(), run.getCompletionTokens(), run.getSnapshotRef() != null && run.getStatus()!=RunStatus.COMMITTING && !run.isSaveRecoveryRequired(), run.isConversational(),
                run.getCreatedAt(), run.getUpdatedAt(), run.getRespondingAt(),run.getCodeSavedAt(),run.getPreviewStatus(),run.isSaveRecoveryRequired(),false,run.getFailureReason());
    }
}
