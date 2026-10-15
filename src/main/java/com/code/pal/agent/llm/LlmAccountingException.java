package com.code.pal.agent.llm;

/** Accounting must be durable before sending or freeing capacity; failure never triggers provider fallback. */
public class LlmAccountingException extends LlmException {
    public LlmAccountingException(Throwable cause) { super("AI accounting is unavailable. Please retry shortly.", cause); }
    public LlmAccountingException(String message) { super(message); }
}
