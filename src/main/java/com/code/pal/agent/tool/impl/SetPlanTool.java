package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.AgentToolException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Creates or replaces the run-local implementation plan. Calling it again is an explicit revision. */
@Component
public class SetPlanTool implements AgentTool {
    @Override public String name() { return "setPlan"; }
    @Override public String description() { return "Create or revise the concrete implementation plan after exploration and whenever findings invalidate it."; }
    @Override public boolean parallelSafe() { return false; }
    @Override public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "steps", Map.of("type", "array", "items", Map.of("type", "string")),
                "reason", Map.of("type", "string")), "required", List.of("steps"));
    }
    @Override public Object execute(Map<String, Object> args, AgentContext context) {
        Object raw = args.get("steps");
        if (!(raw instanceof List<?> values)) throw new AgentToolException("INVALID_PLAN", "'steps' must be an array");
        List<String> steps = values.stream().map(value -> value == null ? "" : value.toString()).toList();
        return context.plan().revise(steps, args.get("reason") == null ? null : args.get("reason").toString());
    }
}
