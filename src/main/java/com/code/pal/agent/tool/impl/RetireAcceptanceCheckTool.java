package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Lets a follow-up edit drop inherited acceptance checks for a feature the user changed or removed. */
@Component
@RequiredArgsConstructor
public class RetireAcceptanceCheckTool implements AgentTool {
    private final RunBehaviorCriteria criteria;

    public String name() {
        return "retireAcceptanceCheck";
    }

    public String description() {
        return "Retire earlier approved acceptance checks that the user's current request intentionally changes or removes "
                + "(for example a renamed heading or a removed feature). Never use it to hide a bug; returns the remaining ids.";
    }

    public boolean parallelSafe() {
        return false;
    }

    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "ids", Map.of("type", "array", "minItems", 1, "maxItems", 24, "items", Map.of("type", "string")),
                "reason", Map.of("type", "string", "maxLength", 300)), "required", List.of("ids", "reason"));
    }

    public Object execute(Map<String, Object> args, AgentContext context) {
        if (!(args.get("ids") instanceof List<?> raw) || raw.stream().anyMatch(id -> !(id instanceof String))) {
            throw new IllegalArgumentException("ids must be a list of check ids");
        }
        List<String> ids = raw.stream().map(String.class::cast).toList();
        Object reason = args.get("reason");
        return Map.of("remaining", criteria.retire(context, ids, reason instanceof String text ? text : null));
    }
}
