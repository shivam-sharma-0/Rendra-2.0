package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Create or overwrite a text file in the project workspace (BUILD mode only). */
@Component
public class WriteFileTool implements AgentTool {

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create or overwrite a file with the given content (path relative to project root).";
    }

    @Override
    public boolean mutating() {
        return true;
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "File path relative to project root"),
                        "content", Map.of("type", "string", "description", "Full file content to write")),
                "required", List.of("path", "content"));
    }

    @Override
    public String execute(Map<String, Object> args, AgentContext context) throws Exception {
        String path = require(args.get("path"), "path");
        String content = args.get("content") == null ? "" : args.get("content").toString();

        Path file = context.resolveSafe(path);
        Files.createDirectories(file.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.write(file, bytes);
        return "Wrote " + bytes.length + " bytes to " + path;
    }

    private static String require(Object v, String name) {
        if (v == null) throw new IllegalArgumentException("missing '" + name + "'");
        return v.toString();
    }
}
