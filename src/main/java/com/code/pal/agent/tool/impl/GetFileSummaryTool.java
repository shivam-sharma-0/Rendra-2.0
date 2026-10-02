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
        if (context.sandboxed()) return summarizeSandbox(path, context);
        Path file = context.resolveSafe(path);
        ProjectFileSummary summary = contexts.summarizeFile(context.workspace(), file);
        context.contextTrace().selected(summary.path(), ContextSelectionReason.FILE_SUMMARY, 300);
        recordLocalImports(file, summary, context);
        return summary;
    }

    private ProjectFileSummary summarizeSandbox(String requested, AgentContext context) {
        String path = context.resolveSandboxPath(requested);
        if (!context.sandbox().regularFile(context.projectId(), context.sandboxRoot(), path)) {
            throw new IllegalArgumentException("File is not available for deterministic summarization: " + requested);
        }
        long size = context.sandbox().size(context.projectId(), context.sandboxRoot(), path);
        String content = context.sandbox().readText(context.projectId(), context.sandboxRoot(), path);
        ProjectFileSummary summary = contexts.summarizeText(path, size, content);
        context.contextTrace().selected(summary.path(), ContextSelectionReason.FILE_SUMMARY, 300);
        recordLocalImportsSandbox(path, summary, context);
        return summary;
    }

    private static void recordLocalImportsSandbox(String source, ProjectFileSummary summary, AgentContext context) {
        Path sourcePath = Path.of(source);
        Path parent = sourcePath.getParent() == null ? Path.of("") : sourcePath.getParent();
        for (String imported : summary.imports()) {
            if (!imported.startsWith(".")) continue;
            String candidate = parent.resolve(imported).normalize().toString().replace('\\', '/');
            if (context.sandbox().regularFile(context.projectId(), context.sandboxRoot(), candidate)) {
                context.contextTrace().selected(candidate, ContextSelectionReason.DIRECT_IMPORT, 0);
                continue;
            }
            for (String extension : SOURCE_EXTENSIONS) {
                String withExtension = candidate + extension;
                if (context.sandbox().regularFile(context.projectId(), context.sandboxRoot(), withExtension)) {
                    context.contextTrace().selected(withExtension, ContextSelectionReason.DIRECT_IMPORT, 0);
                    break;
                }
            }
        }
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
