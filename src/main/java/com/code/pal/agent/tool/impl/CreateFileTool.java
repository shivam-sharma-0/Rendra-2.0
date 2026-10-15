package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CreateFileTool implements AgentTool {
    private final ChangeSetService changeSets;

    @Override
    public String name() {
        return "createFile";
    }

    @Override
    public String description() {
        return "Create a new text file in the isolated change set. Fails if the path already exists.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), "required",
                List.of("path", "content"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        return changeSets.create(context, text(args, "path"), text(args, "content"));
    }

    static String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null)
            throw new IllegalArgumentException("missing '" + key + "'");
        return value.toString();
    }
}
