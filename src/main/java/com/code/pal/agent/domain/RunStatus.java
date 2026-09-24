package com.code.pal.agent.domain;

/**
 * Lifecycle of one agent run (one assistant turn).
 *
 * <ul>
 *   <li>{@code QUEUED} — enqueued, waiting for a worker.</li>
 *   <li>{@code RUNNING} — the loop is executing.</li>
 *   <li>{@code AWAITING_INPUT} — paused on an {@code ask_user} question.</li>
 *   <li>{@code DONE} / {@code FAILED} / {@code CANCELLED} — terminal.</li>
 * </ul>
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    AWAITING_INPUT,
    DONE,
    FAILED,
    CANCELLED
}
