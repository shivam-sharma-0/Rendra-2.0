package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** List entries of a directory in the project workspace (dirs first, capped). */
@Component
public class ListFilesTool implements AgentTool {

    private static final int MAX_ENTRIES = 200;
    /** Never shown: internal state and folders the path rules reject anyway. */
    private static final Set<String> HIDDEN = Set.of(ProjectPaths.INTERNAL, ".git", ".secrets", ".ssh");

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
    public String execute(Map<String, Object> args, AgentContext context) {
        String requested = args.get("path") == null ? "." : args.get("path").toString();
        String path = context.resolveDirectory(requested);
        String root = context.rootFor(path);
        if (!".".equals(path) && (!context.files().exists(context.projectId(), context.root(), path)
                || context.files().regularFile(context.projectId(), context.root(), path))) {
            throw new IllegalArgumentException("Not a directory: " + requested);
        }
        List<ProjectFiles.Entry> entries = context.files().list(context.projectId(), root, 1, HIDDEN).stream()
                .sorted((a, b) -> {
                    if (a.directory() != b.directory()) return a.directory() ? -1 : 1;   // directories first
                    return a.path().compareToIgnoreCase(b.path());
                }).limit(MAX_ENTRIES).toList();
        context.contextTrace().selected(path, ContextSelectionReason.DIRECTORY_DISCOVERY, 0);
        if (entries.isEmpty()) return "(empty)";
        return entries.stream().map(entry -> entry.directory() ? entry.path() + "/" : entry.path())
                .collect(Collectors.joining("\n"));
    }
}
