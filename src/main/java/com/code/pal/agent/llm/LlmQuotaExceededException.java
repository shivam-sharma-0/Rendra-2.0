package com.code.pal.agent.llm;

/** The call was not made: the user's daily AI quota cannot cover it. Not a provider failure, so no fallback. */
public class LlmQuotaExceededException extends LlmException {

    public LlmQuotaExceededException(String message) {
        super(message);
    }
}
