package com.code.pal.agent.contract;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.tool.AgentContext;

import java.util.Set;

/** S7-owned adapter collects server facts and calls ValidationBundles on the staged artifact. */
@FunctionalInterface
public interface ContractValidation {
    ValidationBundle validateBundle(AgentContext context, AgentRun run, BuildContract contract,
            String batchId, Set<String> completedBatchIds);
}
