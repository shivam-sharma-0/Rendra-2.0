package com.code.pal.agent.web;

import com.code.pal.agent.preview.PreviewService;
import com.code.pal.project.service.ProjectService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.UUID;

/**
 * Public serving of a PUBLISHED project's site at {@code /p/{slug}/**}. No authentication — the
 * project is live and public. Only served when the project's deployment status is LIVE.
 */
@RestController
@RequiredArgsConstructor
public class PublicSiteController {

    private final ProjectService projects;
    private final PreviewService preview;

    @GetMapping({"/p/{slug}", "/p/{slug}/**"})
    public ResponseEntity<byte[]> serve(@PathVariable String slug, HttpServletRequest request) {
        if (request.getRequestURI().equals("/p/" + slug)) return PreviewSiteController.slashRedirect(request);
        UUID projectId = projects.resolvePublishedProject(slug);
        String path = relativePath(request.getRequestURI(), slug);
        PreviewService.PreviewFile file = preview.serve(projectId, path.isBlank() ? "index.html" : path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(file.content());
    }

    /** The path after {@code /p/{slug}/} (URL-decoded), or empty for the site root. */
    private static String relativePath(String uri, String slug) {
        String prefix = "/p/" + slug;
        String rest = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        return URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }
}
