package com.code.pal.agent.tool;

import java.util.Map;
import java.util.List;

/**
 * A capability the agent can invoke (read a file, list a dir, run a command, …). Each tool is a
 * Spring bean; the {@link ToolRegistry} collects them all. The model is told about a tool via its
 * {@link #name}, {@link #description}, and {@link #parametersSchema} (JSON Schema), then asks to
 * call it — and {@link #execute} runs the actual work and returns a string result fed back to the model.
 */
public interface AgentTool {

    /** Unique tool name the model uses to call it (e.g. {@code readFile}). */
    String name();

    /** One line telling the model what it does and when to use it. */
    String description();

    /** JSON Schema of the arguments object (properties + required). */
    Map<String, Object> parametersSchema();

    /** Validate untrusted model arguments before any tool implementation is entered. */
    default List<ToolValidationError> validate(Map<String, Object> args) {
        return ToolInputValidator.validate(parametersSchema(), args);
    }

    /** True if the tool changes files/state — only exposed in BUILD mode (CHAT/PLAN stay read-only). */
    default boolean mutating() {
        return false;
    }

    /** False for run-state tools that must be ordered even when they do not modify project files. */
    default boolean parallelSafe() {
        return !mutating();
    }

    /**
     * Execute the tool. {@code args} are the model-supplied arguments; the return value is fed back
     * to the model as the tool result. Throwing signals failure (the loop reports it and continues).
     */
    Object execute(Map<String, Object> args, AgentContext context) throws Exception;
}
