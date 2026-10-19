package com.code.pal.agent.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;
import java.math.BigDecimal;

/**
 * One model call in the call ledger: who it was for, why, which model, what it used and how it ended.
 * {@code purpose} and {@code outcome} are plain text (not database enums), so new values need no
 * schema change. Prompts and replies are never stored.
 */
@Entity
@Table(name = "agent_model_calls", indexes = {
        @Index(name = "ix_agent_model_calls_user_created", columnList = "user_id, created_at"),
        @Index(name = "ix_agent_model_calls_run", columnList = "run_id")
})
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ModelCall {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "attempt_id") UUID attemptId;
    @Column(name = "logical_call_id") UUID logicalCallId;
    @Column(name = "usage_known") Boolean usageKnown;
    @Column(name = "user_id") Long userId;
    @Column(name = "project_id") UUID projectId;
    @Column(name = "run_id") UUID runId;
    @Column(nullable = false, length = 24) String purpose;
    @Column(length = 40) String provider;
    @Column(length = 160) String model;
    /** Tokens the provider reported (0 when it reported none, e.g. a failed or stopped call). */
    @Column(name = "prompt_tokens", nullable = false) int promptTokens;
    @Column(name = "completion_tokens", nullable = false) int completionTokens;
    @Column(name = "cached_input_tokens") Integer cachedInputTokens;
    @Column(name = "reasoning_output_tokens") Integer reasoningOutputTokens;
    @Column(name = "estimated_cost_usd", precision = 30, scale = 12) BigDecimal estimatedCostUsd;
    @Column(name = "cost_estimate_kind", length = 32) String costEstimateKind;
    @Column(name = "price_revision", length = 80) String priceRevision;
    /** The input estimate the quota reserved against. */
    @Column(name = "estimated_input_tokens", nullable = false) int estimatedInputTokens;
    @Column(name = "max_tokens") Integer maxTokens;
    @Column(name = "duration_ms", nullable = false) int durationMs;
    /** OK, FAILED, CANCELLED or REFUSED (not made: quota). */
    @Column(nullable = false, length = 16) String outcome;
    /** A short failure reason, clipped. */
    @Column(length = 300) String error;
    /** The run's own totals already include these tokens (the quota counts the others from here). */
    @Column(name = "counted_in_run", nullable = false) boolean countedInRun;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
}
