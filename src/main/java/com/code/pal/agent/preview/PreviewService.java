package com.code.pal.agent.preview;

import com.code.pal.agent.workspace.WorkspaceStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

    public PreviewFile serve(UUID projectId, String path) {
        Path root = storage.projectDir(projectId).toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw notFound();

        Path target = safeResolve(root, path);
        boolean usable = target != null && Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
        if (!usable) {
            // Directory default + SPA fallback → the site's root index.html.
            Path index = root.resolve("index.html");
            if (!Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) throw notFound();
            target = index;
        }
        try {
            if (Files.size(target) > MAX_BYTES) throw notFound();
            return new PreviewFile(Files.readAllBytes(target), contentType(target));
        } catch (IOException e) {
            throw notFound();
        }
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
