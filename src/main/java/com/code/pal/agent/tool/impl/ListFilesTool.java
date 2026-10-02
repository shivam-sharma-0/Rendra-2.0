package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** List entries of a directory in the project workspace (dirs first, capped). */
@Component
public class ListFilesTool implements AgentTool {

    private static final int MAX_ENTRIES = 200;

    @Override
    public String name() {
        return "listFiles";
    }

    @Override
    public String description() {
        return "List files and folders in a directory (relative to project root). Use '.' for the root.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "Directory path; defaults to '.'")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> args, AgentContext context) throws Exception {
        String path = args.get("path") == null ? "." : args.get("path").toString();
        if (context.sandboxed()) return listSandbox(path, context);
        Path dir = context.resolveSafe(path);
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + path);
        context.contextTrace().selected(path, ContextSelectionReason.DIRECTORY_DISCOVERY, 0);

        List<String> entries = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.sorted((a, b) -> {
                boolean da = Files.isDirectory(a), db = Files.isDirectory(b);
                if (da != db) return da ? -1 : 1;               // directories first
                return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
            }).limit(MAX_ENTRIES).forEach(p ->
                    entries.add(Files.isDirectory(p) ? p.getFileName() + "/" : p.getFileName().toString()));
        }
        if (entries.isEmpty()) return "(empty)";
        return String.join("\n", entries);
    }

    private String listSandbox(String requested, AgentContext context) {
        String path = ".".equals(requested) ? "." : context.resolveSandboxPath(requested);
        String root = ".".equals(path) ? context.sandboxRoot() : context.sandboxRoot() + "/" + path;
        var entries = context.sandbox().list(context.projectId(), root, 1).stream()
                .filter(entry -> entry.path().indexOf('/') < 0)
                .sorted((a, b) -> {
                    if (a.directory() != b.directory()) return a.directory() ? -1 : 1;
                    return a.path().compareToIgnoreCase(b.path());
                }).toList();
        context.contextTrace().selected(requested, ContextSelectionReason.DIRECTORY_DISCOVERY, 0);
        if (entries.isEmpty()) return "(empty)";
        return entries.stream().map(entry -> entry.directory() ? entry.path() + "/" : entry.path())
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
