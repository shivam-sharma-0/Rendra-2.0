package com.code.pal.agent.tool;

/** Expected, structured tool failure that the model may correct and retry. */
public final class AgentToolException extends RuntimeException {
    private final String code;
    private final Object details;

    public AgentToolException(String code, String message) {
        this(code, message, java.util.Map.of());
    }

    public AgentToolException(String code, String message, Object details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public String code() { return code; }
    public Object details() { return details; }
}
