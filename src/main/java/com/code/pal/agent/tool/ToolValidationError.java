package com.code.pal.agent.tool;

/** One machine-readable model-input validation failure. */
public record ToolValidationError(String field, String code, String message) {
}
