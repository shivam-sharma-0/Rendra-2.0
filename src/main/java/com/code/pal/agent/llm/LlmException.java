package com.code.pal.agent.llm;

/** Raised when an LLM provider call fails (network, non-2xx, malformed response). */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
