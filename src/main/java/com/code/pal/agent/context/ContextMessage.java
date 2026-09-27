package com.code.pal.agent.context;

/** One conversation item retained by the deterministic relevance/budget selector. */
public record ContextMessage(String role, String content) {
}
