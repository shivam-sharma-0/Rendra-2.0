package com.code.pal.agent.preview;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Serves a project's files as static content for preview/published sites. Read-only, path-guarded
 * (no traversal or links, sensitive and internal folders never served), size-capped, and falls back to
 * {@code index.html} for directories and unknown paths (SPA routing). No code is executed.
 */
@Service
@RequiredArgsConstructor
public class PreviewService {

    public record PreviewFile(byte[] content, String contentType) {}

    private static final int MAX_BYTES = 10_000_000;
    /** Never public: repository internals, secrets, dependencies, and the backend's own state (history, staging). */
    private static final Set<String> SKIP = Set.of(".git", ".ssh", ".secrets", "node_modules", ProjectPaths.INTERNAL);

    private final ProjectFiles files;
    private final SiteBuildService sites;
    private final PublishedSiteService published;

    /** The published snapshot (for {@code /p/<slug>/}): 404 when nothing is published. */
    public PreviewFile servePublished(UUID projectId, String requested) {
        String site = published.publishedSite(projectId);
        if (site == null) throw notFound();
        return read(projectId, siteFile(projectId, site, safePath(requested)));
    }

    public PreviewFile serve(UUID projectId, String requested) {
        String path = safePath(requested);
        String site = sites.currentSite(projectId);
        return read(projectId, site != null ? siteFile(projectId, site, path) : projectFile(projectId, path));
    }

    /** A file of a site folder that holds its own index.html (a draft of the agent's staged changes). */
    public PreviewFile serveSite(UUID projectId, String site, String requested) {
        return read(projectId, siteFile(projectId, site, safePath(requested)));
    }

    /** The folder under {@code root} holding a static site's entry page ("." for the root), or null. */
    static String entryFolder(ProjectFiles files, UUID projectId, String root) {
        for (String candidate : indexCandidates()) {
            try {
                if (files.regularFile(projectId, root, candidate)) {
                    int slash = candidate.lastIndexOf('/');
                    return slash < 0 ? "." : candidate.substring(0, slash);
                }
            } catch (IllegalArgumentException invalid) {
                // not a servable path
            }
        }
        return null;
    }

    /** Whether the preview has anything to show: a built site, or the files of a project that needs no build. */
    public boolean available(UUID projectId) {
        if (sites.currentSite(projectId) != null) return true;
        return !sites.needsBuild(projectId) && firstFile(projectId, indexCandidates()) != null;
    }

    private PreviewFile read(UUID projectId, String target) {
        if (target == null) throw notFound();
        try {
            return new PreviewFile(files.readBytes(projectId, ".", target, MAX_BYTES), contentType(target));
        } catch (RuntimeException error) {
            throw notFound();
        }
    }

    /**
     * A built site: the file itself, a folder's index.html, an asset referenced relatively from a nested
     * route ({@code a/b/assets/x.js} finds {@code assets/x.js}), else the site's index.html for client-side
     * routes. A missing file with an extension is a real 404, never HTML in place of a script.
     */
    private String siteFile(UUID projectId, String site, String path) {
        List<String> candidates = new ArrayList<>(List.of(site + "/" + path, site + "/" + path + "/index.html"));
        nestedSuffixes(path).forEach(suffix -> candidates.add(site + "/" + suffix));
        String found = firstFile(projectId, candidates);
        if (found != null || hasExtension(path)) return found;
        return firstFile(projectId, List.of(site + "/index.html"));
    }

    /** Project files served as they are (static sites), preferring conventional build folders. */
    private String projectFile(UUID projectId, String path) {
        String found = firstFile(projectId, candidates(path));
        if (found == null && hasExtension(path)) {
            List<String> nested = new ArrayList<>();
            nestedSuffixes(path).forEach(suffix -> nested.addAll(candidates(suffix)));
            found = firstFile(projectId, nested);
        }
        return found != null ? found : firstFile(projectId, indexCandidates());
    }

    /** {@code a/b/c.js} → {@code b/c.js}, {@code c.js}: relative asset URLs requested from a nested route. */
    private static List<String> nestedSuffixes(String path) {
        if (!hasExtension(path)) return List.of();
        List<String> suffixes = new ArrayList<>();
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            suffixes.add(path.substring(slash + 1));
        }
        return suffixes;
    }

    private static boolean hasExtension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.lastIndexOf('.') > 0;
    }

    private String firstFile(UUID projectId, List<String> candidates) {
        for (String candidate : candidates) {
            try {
                if (files.regularFile(projectId, ".", candidate)) return candidate;
            } catch (IllegalArgumentException invalid) {
                // a candidate the path rules reject is simply not served
            }
        }
        return null;
    }

    /** Prefer compiled output, while retaining compatibility with source-only HTML projects. */
    private static List<String> candidates(String path) {
        List<String> result = new ArrayList<>();
        for (String prefix : List.of("dist/", "build/", "", "frontend/dist/", "frontend/build/", "frontend/",
                "client/dist/", "client/build/", "client/", "client/public/")) {
            String candidate = prefix + path;
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

    /** A servable project path, or {@code index.html} for anything invalid or protected. */
    static String safePath(String path) {
        String normalized;
        try {
            normalized = ProjectPaths.normalize(path, false).replaceAll("^(\\./)+", "");
        } catch (IllegalArgumentException invalid) {
            return "index.html";
        }
        for (String part : normalized.split("/")) {
            if (part.equals(".") || SKIP.contains(part.toLowerCase(Locale.ROOT))) return "index.html";
        }
        return normalized.isBlank() ? "index.html" : normalized;
    }

    private static String contentType(String file) {
        String n = file.substring(file.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
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
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".map")) return "application/json; charset=utf-8";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
