package com.code.pal.agent.runtime;

import com.code.pal.agent.domain.AgentRun;

/** Provider-neutral entry point for executing one durable agent run. */
public interface AgentRuntime {
    AgentRunResult run(AgentRun run);
}
