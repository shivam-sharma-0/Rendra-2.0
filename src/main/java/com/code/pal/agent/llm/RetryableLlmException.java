package com.code.pal.agent.llm;

/** Failure for a transient provider or network problem that is safe to retry before output starts. */
public class RetryableLlmException extends LlmException {
    private final long retryAfterMs;
    private final boolean rateLimited;

    public RetryableLlmException(String message, long retryAfterMs) {
        this(message, retryAfterMs, false, null);
    }

    public RetryableLlmException(String message, long retryAfterMs, Throwable cause) {
        this(message, retryAfterMs, false, cause);
    }

    private RetryableLlmException(String message, long retryAfterMs, boolean rateLimited, Throwable cause) {
        super(message, cause);
        this.retryAfterMs = retryAfterMs;
        this.rateLimited = rateLimited;
    }

    /** HTTP 429: the limit belongs to the API key, so another key of the same provider may succeed. */
    public static RetryableLlmException rateLimited(long retryAfterMs) {
        return new RetryableLlmException("HTTP 429", retryAfterMs, true, null);
    }

    public long retryAfterMs() { return retryAfterMs; }

    public boolean rateLimited() { return rateLimited; }
}
