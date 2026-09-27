package com.code.pal.agent.tool;

import java.util.List;

/** Structured boundary result; tool failures are data returned to the model, not leaked exceptions. */
public record ToolExecutionResult(boolean success, Object output, ToolError error) {
    public static ToolExecutionResult success(Object output) {
        return new ToolExecutionResult(true, output, null);
    }

    public static ToolExecutionResult failure(String code, String message) {
        return new ToolExecutionResult(false, null, new ToolError(code, message, List.of()));
    }

    public static ToolExecutionResult failure(String code, String message, Object details) {
        return new ToolExecutionResult(false, null, new ToolError(code, message, details));
    }

    public static ToolExecutionResult validationFailure(List<ToolValidationError> errors) {
        return new ToolExecutionResult(false, null,
                new ToolError("VALIDATION_ERROR", "Tool input did not match its schema", List.copyOf(errors)));
    }

    public record ToolError(String code, String message, Object details) {
    }
}
