package com.code.pal.agent.tool.impl;

import com.code.pal.agent.plan.AgentPlanState.StepStatus;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.AgentToolException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class UpdatePlanStepTool implements AgentTool {
    @Override public String name() { return "updatePlanStep"; }
    @Override public String description() { return "Update a plan step as work progresses; revise the full plan with setPlan when assumptions change."; }
    @Override public boolean parallelSafe() { return false; }
    @Override public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "stepId", Map.of("type", "integer"),
                "status", Map.of("type", "string", "enum", List.of("PENDING", "IN_PROGRESS", "COMPLETED", "SKIPPED")),
                "note", Map.of("type", "string")), "required", List.of("stepId", "status"));
    }
    @Override public Object execute(Map<String, Object> args, AgentContext context) {
        if (!context.plan().exists()) throw new AgentToolException("PLANNING_REQUIRED", "Create a plan before updating its steps");
        int id = ((Number) args.get("stepId")).intValue();
        try {
            StepStatus status = StepStatus.valueOf(args.get("status").toString().toUpperCase(Locale.ROOT));
            return context.plan().updateStep(id, status, args.get("note") == null ? null : args.get("note").toString());
        } catch (IllegalArgumentException error) {
            throw new AgentToolException("INVALID_PLAN_STATUS", "Unknown plan step status");
        }
    }
}
