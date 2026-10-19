package com.code.pal.agent.llm;

/** Failed HTTP-200 stream; its error code is not evidence that the attempt was unbilled. */
public final class LlmStreamException extends LlmException {
    public enum Reason { PROVIDER_ERROR, EMPTY_STREAM, UNEXPECTED_CONTENT_TYPE }

    private final Reason reason;
    private final Integer providerCode;
    private final LlmClient.Usage usage;

    public LlmStreamException(Reason reason, Integer providerCode, LlmClient.Usage usage) {
        super("LLM stream failed: " + reason + (providerCode == null ? "" : " (code " + providerCode + ")"));
        this.reason = reason;
        this.providerCode = providerCode;
        this.usage = usage;
    }

    public Reason reason() { return reason; }
    public Integer providerCode() { return providerCode; }
    public LlmClient.Usage usage() { return usage; }
}
