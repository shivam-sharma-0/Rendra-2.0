package com.code.pal.agent.preview;

import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Serves a project's workspace files as static content for preview/published sites. Read-only,
 * path-guarded (no traversal/symlink escape, sensitive dirs skipped), size-capped, and falls back to
 * {@code index.html} for directories and unknown paths (SPA routing). No code is executed.
 */
@Service
@RequiredArgsConstructor
public class PreviewService {

    public record PreviewFile(byte[] content, String contentType) {}

    private static final long MAX_BYTES = 10_000_000;
    private static final Set<String> SKIP = Set.of(".git", ".ssh", ".secrets", "node_modules");

    private final WorkspaceStorage storage;
    private final SandboxPort sandbox;
    private final AgentProperties properties;

    public PreviewFile serve(UUID projectId, String path) {
        if (properties.getSandbox().isRequired()) return serveSandbox(projectId, path);
        Path root = storage.projectDir(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw notFound();

        Path target = firstHostFile(root, candidates(path));
        boolean usable = target != null && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        if (!usable) {
            // Directory default + SPA fallback → the site's root index.html.
            Path index = firstHostFile(root, indexCandidates());
            if (index == null || !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) throw notFound();
            target = index;
        }
        try {
            if (Files.size(target) > MAX_BYTES) throw notFound();
            return new PreviewFile(Files.readAllBytes(target), contentType(target));
        } catch (IOException e) {
            throw notFound();
        }
    }

    private PreviewFile serveSandbox(UUID projectId, String requested) {
        String path = safeSandboxPath(requested);
        String target = firstSandboxFile(projectId, candidates(path));
        if (target == null) target = firstSandboxFile(projectId, indexCandidates());
        if (target == null) throw notFound();
        try {
            byte[] bytes = sandbox.readBytes(projectId, ".", target, (int) MAX_BYTES);
            return new PreviewFile(bytes, contentType(Path.of(target)));
        } catch (RuntimeException error) {
            throw notFound();
        }
    }

    private static Path firstHostFile(Path root, List<String> candidates) {
        for (String candidate : candidates) {
            Path target = safeResolve(root, candidate);
            if (target != null && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return target;
        }
        return null;
    }

    private String firstSandboxFile(UUID projectId, List<String> candidates) {
        for (String candidate : candidates) {
            if (sandbox.regularFile(projectId, ".", candidate)) return candidate;
        }
        return null;
    }

    /** Prefer compiled output, while retaining compatibility with source-only HTML projects. */
    private static List<String> candidates(String path) {
        String normalized = path.replace('\\', '/');
        List<String> result = new ArrayList<>();
        for (String prefix : List.of("dist/", "build/", "", "frontend/dist/", "frontend/build/", "frontend/",
                "client/dist/", "client/build/", "client/", "client/public/")) {
            String candidate = prefix + normalized;
            if (!result.contains(candidate)) result.add(candidate);
        }
        return result;
    }

    private static List<String> indexCandidates() {
        return List.of("dist/index.html", "build/index.html", "index.html",
                "frontend/dist/index.html", "frontend/build/index.html", "frontend/index.html",
                "client/dist/index.html", "client/build/index.html", "client/index.html",
                "client/public/index.html");
    }

    private static String safeSandboxPath(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0 || Path.of(path).isAbsolute()) return "index.html";
        String value = path.replace('\\', '/');
        for (String part : value.split("/")) {
            if (part.equals("..") || SKIP.contains(part)) return "index.html";
        }
        return value;
    }

    private static Path safeResolve(Path root, String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) return null;
        Path requested = Path.of(path);
        if (requested.isAbsolute()) return null;
        for (Path part : requested) {
            String p = part.toString();
            if (p.equals("..") || SKIP.contains(p)) return null;
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) return null;
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) return null;
        return resolved;
    }

    private static String contentType(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain; charset=utf-8";
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException ignored) {
            // fall through
        }
        return "application/octet-stream";
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
