package com.code.pal.agent.tool.impl;

import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ValidateChangesTool implements AgentTool {
    private final ProjectValidationService validation;

    @Override
    public String name() {
        return "validateChanges";
    }

    @Override
    public String description() {
        return "Run the required structural, TypeScript, and build validation gate for the current staged revision. Must pass after the last mutation before finishing.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        return validation.validateAll(context);
    }
}
