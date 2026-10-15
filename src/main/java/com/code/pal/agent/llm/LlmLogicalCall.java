package com.code.pal.agent.llm;

import java.util.UUID;

/**
 * Binds the logical call identity a caller already owns (for example one structured failover attempt), so the
 * metered ledger records its physical attempts under it and the caller can later prove what happened to them.
 */
public final class LlmLogicalCall {
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private LlmLogicalCall() {
    }

    /** The bound identity, or a fresh one for an ordinary call. */
    public static UUID currentOrNew() {
        UUID bound = CURRENT.get();
        return bound == null ? UUID.randomUUID() : bound;
    }

    public static LlmCallContext.Scope bind(UUID logicalId) {
        UUID previous = CURRENT.get();
        CURRENT.set(logicalId);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }
}
