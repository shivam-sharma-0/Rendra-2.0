package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.context.ProjectFileSummary;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Deterministic metadata/import summary used before deciding whether full file content is needed. */
@Component
public class GetFileSummaryTool implements AgentTool {
    private static final List<String> SOURCE_EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".json", ".css");
    private final ProjectContextService contexts;

    public GetFileSummaryTool(ProjectContextService contexts) {
        this.contexts = contexts;
    }

    @Override
    public String name() {
        return "getFileSummary";
    }

    @Override
    public String description() {
        return "Get compact deterministic metadata, imports, and exports for one file without reading its full content.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string", "description", "File path relative to project root")),
                "required", List.of("path"));
    }

    @Override
    public ProjectFileSummary execute(Map<String, Object> args, AgentContext context) {
        String path = args.get("path").toString();
        Path file = context.resolveSafe(path);
        ProjectFileSummary summary = contexts.summarizeFile(context.workspace(), file);
        context.contextTrace().selected(summary.path(), ContextSelectionReason.FILE_SUMMARY, 300);
        recordLocalImports(file, summary, context);
        return summary;
    }

    private static void recordLocalImports(Path source, ProjectFileSummary summary, AgentContext context) {
        for (String imported : summary.imports()) {
            if (!imported.startsWith(".")) continue;
            Path candidate = source.getParent().resolve(imported).normalize();
            if (Files.isRegularFile(candidate)) {
                select(candidate, context);
                continue;
            }
            for (String extension : SOURCE_EXTENSIONS) {
                Path withExtension = Path.of(candidate + extension);
                if (Files.isRegularFile(withExtension)) {
                    select(withExtension, context);
                    break;
                }
            }
        }
    }

    private static void select(Path file, AgentContext context) {
        Path root = context.workspace().toAbsolutePath().normalize();
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || Files.isSymbolicLink(normalized)) return;
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        context.contextTrace().selected(relative, ContextSelectionReason.DIRECT_IMPORT, 0);
    }
}
