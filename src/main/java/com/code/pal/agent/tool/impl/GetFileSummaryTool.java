package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.context.ProjectFileSummary;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

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
        String path = context.resolvePath(args.get("path").toString());
        ProjectFileSummary summary = contexts.summarizeFile(context.files(), context.projectId(), context.root(), path);
        context.contextTrace().selected(summary.path(), ContextSelectionReason.FILE_SUMMARY, 300);
        recordLocalImports(path, summary, context);
        return summary;
    }

    /** Relative imports that resolve to project files become context candidates for later reads. */
    private static void recordLocalImports(String source, ProjectFileSummary summary, AgentContext context) {
        Path parent = Path.of(source).getParent() == null ? Path.of("") : Path.of(source).getParent();
        for (String imported : summary.imports()) {
            if (!imported.startsWith(".")) continue;
            String candidate;
            try {
                candidate = context.resolvePath(parent.resolve(imported).normalize().toString().replace('\\', '/'));
            } catch (IllegalArgumentException outsideProject) {
                continue;
            }
            if (context.files().regularFile(context.projectId(), context.root(), candidate)) {
                context.contextTrace().selected(candidate, ContextSelectionReason.DIRECT_IMPORT, 0);
                continue;
            }
            for (String extension : SOURCE_EXTENSIONS) {
                String withExtension = candidate + extension;
                if (context.files().regularFile(context.projectId(), context.root(), withExtension)) {
                    context.contextTrace().selected(withExtension, ContextSelectionReason.DIRECT_IMPORT, 0);
                    break;
                }
            }
        }
    }
}
