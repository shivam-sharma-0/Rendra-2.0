package com.code.pal.agent.dto;

/** A single workspace file's text content (capped), for the code viewer. */
public record FileContentResponse(String path, String content, boolean truncated, boolean binary) {
}
