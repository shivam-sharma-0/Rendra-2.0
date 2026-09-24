package com.code.pal.agent.tool;

import java.util.Map;

/**
 * A capability the agent can invoke (read a file, list a dir, run a command, …). Each tool is a
 * Spring bean; the {@link ToolRegistry} collects them all. The model is told about a tool via its
 * {@link #name}, {@link #description}, and {@link #parametersSchema} (JSON Schema), then asks to
 * call it — and {@link #execute} runs the actual work and returns a string result fed back to the model.
 */
public interface AgentTool {

    /** Unique tool name the model uses to call it (e.g. {@code read_file}). */
    String name();

    /** One line telling the model what it does and when to use it. */
    String description();

    /** JSON Schema of the arguments object (properties + required). */
    Map<String, Object> parametersSchema();

    /** True if the tool changes files/state — only exposed in BUILD mode (CHAT/PLAN stay read-only). */
    default boolean mutating() {
        return false;
    }

    /**
     * Execute the tool. {@code args} are the model-supplied arguments; the return value is fed back
     * to the model as the tool result. Throwing signals failure (the loop reports it and continues).
     */
    String execute(Map<String, Object> args, AgentContext context) throws Exception;
}
