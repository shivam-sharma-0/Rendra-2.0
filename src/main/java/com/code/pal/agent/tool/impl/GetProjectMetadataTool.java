package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentProjectMetadata;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Returns the already-authorized project metadata loaded by the runtime. */
@Component
public class GetProjectMetadataTool implements AgentTool {
    @Override
    public String name() {
        return "getProjectMetadata";
    }

    @Override
    public String description() {
        return "Return safe metadata for the current project, including its name, mode, workspace, and deployment state.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, AgentContext context) {
        AgentProjectMetadata metadata = context.projectMetadata();
        if (metadata == null) throw new IllegalStateException("Project metadata was not loaded by the runtime");
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("projectId", metadata.projectId().toString());
        output.put("workspaceId", metadata.workspaceId().toString());
        output.put("name", metadata.name());
        output.put("type", metadata.type().name());
        output.put("public", metadata.isPublic());
        output.put("deploymentStatus", metadata.deploymentStatus().name());
        if (metadata.createdAt() != null) output.put("createdAt", metadata.createdAt().toString());
        if (metadata.updatedAt() != null) output.put("updatedAt", metadata.updatedAt().toString());
        return output;
    }
}
