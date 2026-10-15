package com.code.pal.agent.contract;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.ValidationReport;

/** Stack-owned execution adapter: durable bounded claim, fixed lock refresh and one guarded atomic source apply. */
@FunctionalInterface
public interface ContractFixes {
    boolean tryFix(AgentContext context, AgentRun run, BuildContract contract, ValidationReport failure);
}
