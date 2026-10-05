package com.code.pal.agent.web;

import com.code.pal.agent.preview.PreviewService;
import com.code.pal.identity.security.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Serves a private project's generated files through a short-lived signed preview URL. */
@RestController
@RequiredArgsConstructor
public class PreviewSiteController {
    private final JwtService jwt;
    private final PreviewService preview;

    @GetMapping({"/preview/{token}", "/preview/{token}/**"})
    public ResponseEntity<byte[]> serve(@PathVariable String token, HttpServletRequest request) {
        // Built sites reference assets relatively; resolved against a URL without the slash they would miss.
        if (request.getRequestURI().equals("/preview/" + token)) return slashRedirect(request);
        try {
            UUID projectId = jwt.previewProjectId(jwt.parse(token));
            String path = relativePath(request.getRequestURI(), token);
            PreviewService.PreviewFile file = preview.serve(projectId, path.isBlank() ? "index.html" : path);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(file.content());
        } catch (RuntimeException error) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Preview not found");
        }
    }

    static ResponseEntity<byte[]> slashRedirect(HttpServletRequest request) {
        String query = request.getQueryString();
        return ResponseEntity.status(HttpStatus.PERMANENT_REDIRECT)
                .header(HttpHeaders.LOCATION, request.getRequestURI() + "/" + (query == null ? "" : "?" + query)).build();
    }

    private static String relativePath(String uri, String token) {
        String prefix = "/preview/" + token;
        String rest = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
        if (rest.startsWith("/")) rest = rest.substring(1);
        return URLDecoder.decode(rest, StandardCharsets.UTF_8);
    }
}
