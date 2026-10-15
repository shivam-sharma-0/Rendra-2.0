package com.code.pal.agent.llm;

/** The call was not made: the user's daily AI quota cannot cover it. Not a provider failure, so no fallback. */
public class LlmQuotaExceededException extends LlmException {

    public LlmQuotaExceededException(String message) {
        super(message);
    }
    public boolean runBudget(){return getMessage()!=null && getMessage().contains("run's token budget");}
}
