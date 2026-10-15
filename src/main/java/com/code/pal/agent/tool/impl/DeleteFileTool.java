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
public class DeleteFileTool implements AgentTool {
    private final ChangeSetService changeSets;

    @Override
    public String name() {
        return "deleteFile";
    }

    @Override
    public String description() {
        return "Delete an existing file from the isolated change set using an exact expected version.";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties",
                Map.of("path", Map.of("type", "string"), "expectedVersion", Map.of("type", "string")), "required",
                List.of("path", "expectedVersion"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        return changeSets.delete(context, CreateFileTool.text(args, "path"),
                CreateFileTool.text(args, "expectedVersion"));
    }
}
