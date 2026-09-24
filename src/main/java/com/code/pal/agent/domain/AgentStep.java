package com.code.pal.agent.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single recorded step in a run's timeline (a thought, a tool call/result, a question, the final
 * message). Persisted so the UI can replay the timeline on reload and a dropped client can catch up.
 *
 * <p>{@code seq} orders steps within a run (also used by clients to dedupe replayed events). The
 * {@code (run_id, seq)} index makes ordered retrieval a single range scan.
 */
@Entity
@Table(
        name = "agent_steps",
        indexes = @Index(name = "ix_agent_steps_run_seq", columnList = "run_id, seq")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "run_id", nullable = false, updatable = false)
    UUID runId;

    /** Monotonic order within the run. */
    @Column(nullable = false, updatable = false)
    int seq;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    AgentStepType type;

    /** Human-facing text (thought text, message chunk, tool name). */
    @Column(columnDefinition = "text")
    String content;

    /** Structured detail as JSON (tool args/result, question payload). Null for plain text steps. */
    @Column(columnDefinition = "text")
    String data;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
