package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates independent new files in one ordered change-set operation. */
@Component
@RequiredArgsConstructor
public class CreateFilesTool implements AgentTool {
    private final ChangeSetService changeSets;

    @Override
    public String name() { return "createFiles"; }

    @Override
    public String description() {
        return "Create several independent new text files in one ordered operation. Prefer this for a project scaffold; use createFile for one file and patchFile for existing files.";
    }

    @Override
    public boolean mutating() { return true; }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("files", Map.of(
                        "type", "array",
                        "items", Map.of("type", "object", "properties", Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                                "required", List.of("path", "content")))),
                "required", List.of("files"));
    }

    @Override
    public Object execute(Map<String, Object> args, AgentContext context) {
        Object raw = args.get("files");
        if (!(raw instanceof List<?> files) || files.isEmpty()) {
            throw new IllegalArgumentException("files must contain at least one file");
        }
        if (files.size() > 24) throw new IllegalArgumentException("A single batch may contain at most 24 files");

        List<String> created = new ArrayList<>();
        for (Object item : files) {
            if (!(item instanceof Map<?, ?> rawFile)) {
                throw new IllegalArgumentException("Each files entry must be an object");
            }
            String path = value(rawFile, "path");
            String content = value(rawFile, "content");
            changeSets.create(context, path, content);
            created.add(path);
        }
        return Map.of("createdFiles", List.copyOf(created));
    }

    private static String value(Map<?, ?> item, String key) {
        Object value = item.get(key);
        if (value == null || value.toString().isBlank()) throw new IllegalArgumentException("missing '" + key + "'");
        return value.toString();
    }
}
