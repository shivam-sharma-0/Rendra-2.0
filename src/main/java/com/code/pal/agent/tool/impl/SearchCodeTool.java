package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
    private static final long MAX_FILE_BYTES = 300_000;
    private static final int MAX_LINE_CHARS = 500;
    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", ".ssh", ".secrets", "node_modules", "dist", "build", "target", ".next", ".cache");

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
    public Map<String, Object> execute(Map<String, Object> args, AgentContext context) throws IOException {
        String query = args.get("query").toString();
        String path = args.getOrDefault("path", ".").toString();
        boolean caseSensitive = Boolean.TRUE.equals(args.get("caseSensitive"));
        int maxResults = args.get("maxResults") instanceof Number number
                ? Math.min(MAX_RESULTS, number.intValue()) : DEFAULT_MAX_RESULTS;

        if (context.sandboxed()) return searchSandbox(query, path, caseSensitive, maxResults, context);

        Path root = context.resolveSafe(path);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Search path is not a directory: " + path);
        }

        String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        List<Map<String, Object>> matches = new ArrayList<>();
        int[] scannedFiles = {0};
        boolean[] truncated = {false};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && (Files.isSymbolicLink(dir) || isSkipped(dir.getFileName().toString()))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (matches.size() >= maxResults || scannedFiles[0] >= MAX_FILES) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                if (!attrs.isRegularFile() || Files.isSymbolicLink(file) || attrs.size() > MAX_FILE_BYTES
                        || isSecretFile(file.getFileName().toString()) || looksBinary(file)) {
                    return FileVisitResult.CONTINUE;
                }
                scannedFiles[0]++;
                searchFile(file, needle, caseSensitive, context, matches, maxResults);
                if (matches.size() >= maxResults) truncated[0] = true;
                return matches.size() >= maxResults ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }
        });

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("path", path);
        output.put("matches", matches);
        output.put("matchCount", matches.size());
        output.put("scannedFiles", scannedFiles[0]);
        output.put("truncated", truncated[0]);
        return output;
    }

    private Map<String, Object> searchSandbox(String query, String requestedPath, boolean caseSensitive,
                                              int maxResults, AgentContext context) {
        if (query.isBlank() || query.length() > 256) throw new IllegalArgumentException("Search query must be 1-256 characters");
        String path = ".".equals(requestedPath) ? "." : context.resolveSandboxPath(requestedPath);
        String root = ".".equals(path) ? context.sandboxRoot() : context.sandboxRoot() + "/" + path;
        List<String> command = new ArrayList<>(List.of("grep", "-RInF", "-I"));
        for (String ignored : SKIP_DIRECTORIES) command.add("--exclude-dir=" + ignored);
        if (!caseSensitive) command.add("--ignore-case");
        command.add("--");
        command.add(query);
        command.add(".");
        var result = context.sandbox().exec(context.projectId(), root, command);
        List<Map<String, Object>> matches = new ArrayList<>();
        if (result.success() || result.exitCode() == 1) {
            for (String line : result.stdout().split("\\R")) {
                if (matches.size() >= maxResults || line.isBlank()) break;
                int first = line.indexOf(':');
                int second = first < 0 ? -1 : line.indexOf(':', first + 1);
                if (first < 0 || second < 0) continue;
                String foundPath = line.substring(0, first).replaceFirst("^\\./", "");
                int lineNumber;
                try { lineNumber = Integer.parseInt(line.substring(first + 1, second)); }
                catch (NumberFormatException ignored) { continue; }
                String snippet = line.substring(second + 1);
                if (snippet.length() > MAX_LINE_CHARS) snippet = snippet.substring(0, MAX_LINE_CHARS) + "...";
                matches.add(Map.of("path", foundPath, "line", lineNumber, "text", snippet));
                context.contextTrace().selected(foundPath, ContextSelectionReason.SEARCH_MATCH, snippet.length());
            }
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("query", query);
        output.put("path", requestedPath);
        output.put("matches", matches);
        output.put("matchCount", matches.size());
        output.put("scannedFiles", 0);
        output.put("truncated", matches.size() >= maxResults || result.stdout().split("\\R").length > maxResults);
        return output;
    }

    private static void searchFile(Path file, String needle, boolean caseSensitive, AgentContext context,
                                   List<Map<String, Object>> matches, int maxResults) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null && matches.size() < maxResults) {
                lineNumber++;
                String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                if (haystack.contains(needle)) {
                    String snippet = line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + "...";
                    String relative = context.workspace().toAbsolutePath().normalize()
                            .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                    matches.add(Map.of(
                            "path", relative,
                            "line", lineNumber,
                            "text", snippet));
                    context.contextTrace().selected(relative, ContextSelectionReason.SEARCH_MATCH, snippet.length());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable/non-text files are skipped; a single file must not fail the whole search.
        }
    }

    private static boolean isSkipped(String name) {
        return SKIP_DIRECTORIES.stream().anyMatch(item -> item.equalsIgnoreCase(name));
    }

    private static boolean isSecretFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals(".env") || (lower.startsWith(".env.") && !lower.endsWith(".example"))
                || lower.endsWith(".pem") || lower.endsWith(".key") || lower.endsWith(".p12")
                || lower.endsWith(".pfx") || lower.equals(".npmrc") || lower.equals("credentials")
                || lower.startsWith("id_rsa") || lower.startsWith("id_ed25519");
    }

    private static boolean looksBinary(Path file) {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] prefix = input.readNBytes(8_000);
            for (byte value : prefix) {
                if (value == 0) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
