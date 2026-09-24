package com.code.pal.agent.domain;

/**
 * A step recorded during a run — the persisted timeline the UI replays (thoughts, tool calls,
 * questions, final message). Mirrors the realtime event types.
 */
public enum AgentStepType {
    THINKING,
    TOOL_CALL,
    TOOL_RESULT,
    ASK_USER,
    MESSAGE,
    STATUS
}
