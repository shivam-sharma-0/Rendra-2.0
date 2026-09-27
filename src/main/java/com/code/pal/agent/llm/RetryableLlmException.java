package com.code.pal.agent.llm;

/** Failure for a transient provider or network problem that is safe to retry before output starts. */
public class RetryableLlmException extends LlmException {
    private final long retryAfterMs;

    public RetryableLlmException(String message, long retryAfterMs) {
        super(message);
        this.retryAfterMs = retryAfterMs;
    }

    public RetryableLlmException(String message, long retryAfterMs, Throwable cause) {
        super(message, cause);
        this.retryAfterMs = retryAfterMs;
    }

    public long retryAfterMs() { return retryAfterMs; }
}
