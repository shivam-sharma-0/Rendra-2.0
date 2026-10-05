package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded literal text search over source files without granting shell access. */
@Component
public class SearchCodeTool implements AgentTool {
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int MAX_RESULTS = 100;
    private static final int MAX_FILES = 5_000;
    private static final int MAX_DEPTH = 24;
    private static final long MAX_FILE_BYTES = 300_000;
    private static final int MAX_LINE_CHARS = 500;
    /** Never entered: dependencies, build output, internal state and secrets. */
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", ".ssh", ".secrets", ProjectPaths.INTERNAL, "node_modules", "dist", "build", "target", ".next", ".cache");

    @Override
    public String name() {
        return "searchCode";
    }

    @Override
    public String description() {
        return "Search project text files for a literal string and return matching paths, line numbers, and snippets.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Literal text to find", "maxLength", 256),
                        "path", Map.of("type", "string", "description", "Directory to search; defaults to '.'"),
                        "caseSensitive", Map.of("type", "boolean", "description", "Whether letter case must match"),
                        "maxResults", Map.of("type", "integer", "minimum", 1, "maximum", MAX_RESULTS)),
                "required", List.of("query"));
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> args, AgentContext context) {
        String query = args.get("query").toString();
        if (query.isBlank() || query.length() > 256) throw new IllegalArgumentException("Search query must be 1-256 characters");
        String requested = args.getOrDefault("path", ".").toString();
        boolean caseSensitive = Boolean.TRUE.equals(args.get("caseSensitive"));
        int maxResults = args.get("maxResults") instanceof Number number
                ? Math.max(1, Math.min(MAX_RESULTS, number.intValue())) : DEFAULT_MAX_RESULTS;

        String directory = context.resolveDirectory(requested);
        if (!".".equals(directory) && (!context.files().exists(context.projectId(), context.root(), directory)
                || context.files().regularFile(context.projectId(), context.root(), directory))) {
            throw new IllegalArgumentException("Search path is not a directory: " + requested);
        }
        String prefix = ".".equals(directory) ? "" : directory + "/";
        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        List<ProjectFiles.Entry> candidates = context.files()
                .list(context.projectId(), context.rootFor(directory), MAX_DEPTH, SKIP_DIRECTORIES).stream()
                .filter(entry -> !entry.directory() && entry.size() <= MAX_FILE_BYTES)
                .filter(entry -> !isSecretFile(entry.path().substring(entry.path().lastIndexOf('/') + 1)))
                .sorted(Comparator.comparing(ProjectFiles.Entry::path))
                .toList();

        List<Map<String, Object>> matches = new ArrayList<>();
        int scannedFiles = 0;
        boolean truncated = false;
        for (ProjectFiles.Entry entry : candidates) {
            if (matches.size() >= maxResults || scannedFiles >= MAX_FILES) {
                truncated = true;
                break;
            }
            String path = prefix + entry.path();
            byte[] bytes;
            try {
                bytes = context.files().readHead(context.projectId(), context.root(), path, (int) MAX_FILE_BYTES);
            } catch (RuntimeException unreadable) {
                continue;   // a single file must not fail the whole search
            }
            if (isBinary(bytes)) continue;
            scannedFiles++;
            searchText(path, new String(bytes, StandardCharsets.UTF_8), needle, caseSensitive, context, matches, maxResults);
            if (matches.size() >= maxResults) truncated = true;
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("path", requested);
        output.put("matches", matches);
        output.put("matchCount", matches.size());
        output.put("scannedFiles", scannedFiles);
        output.put("truncated", truncated);
        return output;
    }

    private static void searchText(String path, String text, String needle, boolean caseSensitive, AgentContext context,
                                   List<Map<String, Object>> matches, int maxResults) {
        int lineNumber = 0;
        for (String line : text.split("\\R", -1)) {
            if (matches.size() >= maxResults) return;
            lineNumber++;
            String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
            if (!haystack.contains(needle)) continue;
            String snippet = line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "...";
            matches.add(Map.of("path", path, "line", lineNumber, "text", snippet));
            context.contextTrace().selected(path, ContextSelectionReason.SEARCH_MATCH, snippet.length());
        }
    }

    private static boolean isSecretFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(".env") || (lower.startsWith(".env.") && !lower.endsWith(".example"))
                || lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12")
                || lower.endsWith(".pfx") || lower.equals(".npmrc") || lower.equals("credentials")
                || lower.startsWith("id_rsa") || lower.startsWith("id_ed25519");
    }

    private static boolean isBinary(byte[] bytes) {
        for (int i = 0; i < Math.min(bytes.length, 8_000); i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }
}
