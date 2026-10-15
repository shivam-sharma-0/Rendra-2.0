package com.code.pal.agent.context;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.security.NoSuchAlgorithmException;

/** A bounded per-query index of the requested staging root; no stale cross-run cache or model call. */
@Service
public final class DeterministicProjectRetrieval implements ProjectRetrieval {
    private static final Set<String> SKIP = Set.of("node_modules", "dist", "build", "target", "coverage", ".git",
            ".codepal", ".secrets", ".ssh");
    private static final Set<String> TEXT = Set.of("ts", "tsx", "js", "jsx", "mjs", "cjs", "java", "py", "go", "rs",
            "json", "css", "html");
    private static final Set<String> ASSETS = Set.of("svg", "png", "jpg", "jpeg", "gif", "webp", "avif", "ico", "woff",
            "woff2", "mp3", "mp4");
    private static final List<String> EXTENSIONS = List.of(".ts", ".tsx", ".js", ".jsx", ".mjs", ".json", "/index.ts",
            "/index.tsx", "/index.js");
    private static final int HEAD_BYTES = 16_000;
    private final AgentProperties properties;
    private final ProjectContextService contexts;

    public DeterministicProjectRetrieval(AgentProperties properties, ProjectContextService contexts) {
        this.properties = properties;
        this.contexts = contexts;
    }

    @Override
    public Result search(ProjectFiles files, UUID projectId, String root, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > 1000 || limit < 1 || limit > 20)
            throw new IllegalArgumentException("query must be 1-1000 characters; limit 1-20");
        root = ProjectPaths.normalize(root, true);
        List<ProjectFiles.Entry> entries = files.list(projectId, root, Math.max(1, properties.getProjectScanMaxDepth()),
                SKIP);
        Map<String, Match> indexed = new TreeMap<>();
        boolean partial = false;
        int scanned = 0;
        for (var entry : entries.stream().sorted(Comparator.comparing(ProjectFiles.Entry::path)).toList()) {
            String path = entry.path();
            if (entry.directory() || excluded(path))
                continue;
            String ext = extension(path);
            boolean asset = ASSETS.contains(ext);
            if (!asset && !TEXT.contains(ext))
                continue;
            if (scanned >= Math.max(1, properties.getProjectScanMaxFiles())) {
                partial = true;
                break;
            }
            scanned++;
            try {
                String version = files.version(projectId, root, path);
                if (!version.startsWith("sha256:")) {
                    partial = true;
                    continue;
                }
                var summary = asset ? null
                        : contexts.summarizeText(path, entry.size(),
                                new String(files.readHead(projectId, root, path, HEAD_BYTES), StandardCharsets.UTF_8));
                if (!version.equals(files.version(projectId, root, path))) {
                    partial = true;
                    continue;
                }
                indexed.put(path, new Match(path, version, entry.size(), asset, !asset && entry.size() > HEAD_BYTES,
                        asset ? List.of() : summary.exports(), asset ? List.of() : summary.imports(), List.of()));
            } catch (RuntimeException unreadable) {
                partial = true;
            }
        }
        Map<String, List<String>> outgoing = new HashMap<>(), incoming = new HashMap<>();
        for (var file : indexed.values())
            for (String imported : file.imports()) {
                String target = resolve(file.path(), imported, indexed.keySet());
                if (target == null)
                    continue;
                outgoing.computeIfAbsent(file.path(), key -> new ArrayList<>()).add(target);
                incoming.computeIfAbsent(target, key -> new ArrayList<>()).add(file.path());
            }
        Set<String> terms = terms(query);
        Map<String, Integer> scores = new HashMap<>();
        for (var file : indexed.values()) {
            int score = 0;
            Set<String> pathTerms = terms(file.path());
            Set<String> symbols = terms(String.join(" ", file.symbols()));
            for (String term : terms) {
                if (pathTerms.contains(term))
                    score += 4;
                if (symbols.contains(term))
                    score += 8;
            }
            if (query.contains(file.path()))
                score += 20;
            if (score > 0)
                scores.put(file.path(), score);
        }
        for (String seed : List.copyOf(scores.keySet())) {
            for (String related : outgoing.getOrDefault(seed, List.of()))
                scores.merge(related, 2, Integer::sum);
            for (String related : incoming.getOrDefault(seed, List.of()))
                scores.merge(related, 1, Integer::sum);
        }
        List<Match> matches = scores.keySet().stream()
                .sorted(Comparator.comparingInt((String p) -> scores.get(p)).reversed().thenComparing(p -> p))
                .limit(limit)
                .map(p -> {
                    var m = indexed.get(p);
                    return new Match(p, m.version(), m.sizeBytes(), m.asset(), m.summaryPartial(), m.symbols(),
                            m.imports(), List.copyOf(incoming.getOrDefault(p, List.of())));
                }).toList();
        return new Result(digest(indexed), scanned, partial || scores.size() > limit, matches);
    }

    private static boolean excluded(String path) {
        try {
            ProjectPaths.agentPath(path, false);
        } catch (IllegalArgumentException invalid) {
            return true;
        }
        for (String segment : path.replace('\\', '/').split("/"))
            if (SKIP.contains(segment.toLowerCase(Locale.ROOT)) || segment.startsWith(".")
                    || ReadFileTool.isSecretFile(segment))
                return true;
        return path.toLowerCase(Locale.ROOT).contains("rendra.md");
    }

    private static String extension(String p) {
        int dot = p.lastIndexOf('.');
        return dot < 0 ? "" : p.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> terms(String text) {
        return new HashSet<>(Arrays
                .asList(text.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9_$]+")));
    }

    private static String resolve(String source, String imported, Set<String> paths) {
        if (!imported.startsWith("."))
            return null;
        try {
            Path parent = Path.of(source).getParent();
            String p = ProjectPaths
                    .agentPath((parent == null ? Path.of("") : parent).resolve(imported).normalize().toString(), false);
            if (paths.contains(p))
                return p;
            for (String ext : EXTENSIONS)
                if (paths.contains(p + ext))
                    return p + ext;
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static String digest(Map<String, Match> files) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            files.forEach((p, m) -> hash.update((p + "\0" + m.version() + "\n").getBytes(StandardCharsets.UTF_8)));
            return "sha256:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
