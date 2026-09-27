package com.code.pal.agent.runtime;

/** Fine-grained in-memory phase of the bounded orchestration loop. */
public enum AgentRuntimeStatus {
    CREATED,
    RUNNING,
    WAITING_FOR_MODEL,
    EXECUTING_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED
}
