package com.code.pal.agent.llm;

/** The provider answered with an error status that retrying the same request will not fix. */
public class LlmHttpException extends LlmException {
    private final int status;

    public LlmHttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
    /** Invalid payload/context must be corrected, not repeated across every configured model. */
    public boolean terminalRequest(){return status==400 || status==413 || status==422;}

    /**
     * The model is not usable with this account for a while: access denied for the model (403, e.g. a
     * model the free tier does not serve), unknown model (404) or a model gone (410).
     */
    public boolean modelUnavailable() {
        return status == 403 || status == 404 || status == 410;
    }
}
