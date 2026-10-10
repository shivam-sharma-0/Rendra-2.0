package com.code.pal.agent.llm;

import java.util.UUID;

/**
 * Who a model call is made for and why, bound to the calling thread (like the run's Stop signal), so the
 * metered client can record and reserve every call without each caller passing it along.
 *
 * @param countedInRun the call's tokens are added to its run's totals by the agent loop (so the quota
 *                     already counts them there); other calls are counted from the call ledger
 */
public record LlmCallContext(Long userId, UUID projectId, UUID runId, CallPurpose purpose, boolean countedInRun) {

    /** Outside any request: no user, nothing reserved, recorded as OTHER. */
    public static final LlmCallContext UNKNOWN = new LlmCallContext(null, null, null, CallPurpose.OTHER, false);

    private static final ThreadLocal<LlmCallContext> CURRENT = new ThreadLocal<>();

    public static LlmCallContext current() {
        LlmCallContext bound = CURRENT.get();
        return bound == null ? UNKNOWN : bound;
    }

    /** Binds {@code context} to this thread until the scope is closed (restores the previous one). */
    public static Scope bind(LlmCallContext context) {
        LlmCallContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    /** The current caller with another purpose, e.g. the intent or vision call a run makes. */
    public static Scope purpose(CallPurpose purpose, boolean countedInRun) {
        LlmCallContext current = current();
        return bind(new LlmCallContext(current.userId(), current.projectId(), current.runId(), purpose, countedInRun));
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
