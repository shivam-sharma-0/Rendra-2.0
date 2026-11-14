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
        return "Run every required check on the current staged revision: structure, type check, build, the browser "
                + "check and, for apps with a server, the API behaviour checks. Failures name the file, step or route. "
                + "Must pass after the last mutation before finishing.";
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
