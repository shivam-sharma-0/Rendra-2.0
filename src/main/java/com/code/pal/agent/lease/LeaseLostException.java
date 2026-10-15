package com.code.pal.agent.lease;

/** A stale worker must stop without writing terminal state over its replacement. */
public final class LeaseLostException extends RuntimeException {
    public LeaseLostException() { super("Worker ownership expired or changed"); }
}
