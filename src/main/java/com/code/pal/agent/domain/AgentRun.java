package com.code.pal.agent.domain;

import com.code.pal.project.domain.ProjectType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One agent run = one assistant turn being produced for a project. */
@Entity
@Table(name = "agent_runs", indexes = {
        @Index(name = "ix_agent_runs_project_status", columnList = "project_id, status"),
        @Index(name = "ix_agent_runs_status_updated", columnList = "status, updated_at"),
        @Index(name = "ix_agent_runs_status_heartbeat", columnList = "status, heartbeat_at"),
        @Index(name = "ix_agent_runs_user_created", columnList = "user_id, created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AgentRun {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "project_id", nullable = false, updatable = false) UUID projectId;
    @Column(name = "message_id", length = 40) String messageId;
    @Column(name = "request_message_id", length = 40, updatable = false) String requestMessageId;
    @Column(name = "request_fingerprint", length = 64, updatable = false) String requestFingerprint;
    /** Explicit retry reads a private unverified artifact into a new isolated stage, never the live tree. */
    @Column(name = "retry_source_run_id", updatable = false) UUID retrySourceRunId;
    @Column(name = "user_id", nullable = false, updatable = false) Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) ProjectType mode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) @Builder.Default RunStatus status = RunStatus.QUEUED;
    @Column(name = "prompt_tokens", nullable = false) @Builder.Default int promptTokens = 0;
    @Column(name = "completion_tokens", nullable = false) @Builder.Default int completionTokens = 0;
    /** True when this turn took the conversational fast path (small talk) instead of the full agent pipeline. */
    @Column(name = "conversational", nullable = false) @ColumnDefault("false") @Builder.Default boolean conversational = false;
    /** Internal Git ref containing the workspace before this BUILD run. */
    @Column(name = "snapshot_ref", length = 160) String snapshotRef;
    /** Set once, on the first streamed content token — marks where "thinking" ends and the visible
     * reply begins, so the two can be timed separately from the run's total duration. */
    @Column(name = "responding_at") Instant respondingAt;
    @Column(columnDefinition = "text") String error;
    @Enumerated(EnumType.STRING) @Column(name="failure_reason",length=48) AgentFailureReason failureReason;
    /** Last liveness beat from the executing worker; drives stale-run recovery without killing live runs. */
    @Column(name = "heartbeat_at") Instant heartbeatAt;
    /** First Stop actor/time; bulk-updated so stale runner saves cannot erase the audit. */
    @Column(name = "cancel_requested_by", updatable = false) Long cancelRequestedBy;
    @Column(name = "cancel_requested_at", updatable = false) Instant cancelRequestedAt;
    @Column(name = "commit_started_at", updatable = false) Instant commitStartedAt;
    @Column(name = "code_saved_at", updatable = false) Instant codeSavedAt;
    @Column(name = "preview_status", updatable = false, length = 24) String previewStatus;
    @Column(name = "save_recovery_required", nullable=false, updatable=false) @Builder.Default boolean saveRecoveryRequired=false;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
