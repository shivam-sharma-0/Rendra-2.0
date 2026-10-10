package com.code.pal.agent.llm;

/** The run was stopped while a model call was waiting or streaming; never retried or failed over. */
public class LlmCancelledException extends LlmException {

    public LlmCancelledException() {
        super("LLM call cancelled");
    }
}
