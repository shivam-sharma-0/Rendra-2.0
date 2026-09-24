package com.code.pal.agent.domain;

import com.code.pal.project.domain.ProjectType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One agent run = one assistant turn being produced for a project.
 *
 * <p>Tracks lifecycle {@link RunStatus}, the assistant {@link #messageId} it fills, token usage
 * (credits/analytics), and errors. Indexed on {@code (project_id, status)} to find a project's
 * active run (concurrency lock) and on {@code (status, updated_at)} to sweep stale/abandoned runs
 * after a crash.
 */
@Entity
@Table(
        name = "agent_runs",
        indexes = {
                @Index(name = "ix_agent_runs_project_status", columnList = "project_id, status"),
                @Index(name = "ix_agent_runs_status_updated", columnList = "status, updated_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AgentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    UUID projectId;

    /** The assistant message this run streams into (client ULID). */
    @Column(name = "message_id", length = 40)
    String messageId;

    /** User who triggered the run. */
    @Column(name = "user_id", nullable = false, updatable = false)
    Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    ProjectType mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    RunStatus status = RunStatus.QUEUED;

    @Column(name = "prompt_tokens", nullable = false)
    @Builder.Default
    int promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    @Builder.Default
    int completionTokens = 0;

    /** Failure reason when {@code status == FAILED}. */
    @Column(columnDefinition = "text")
    String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;
}
