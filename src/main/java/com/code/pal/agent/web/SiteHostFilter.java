package com.code.pal.agent.web;

import com.code.pal.agent.preview.DraftPreviewService;
import com.code.pal.agent.preview.PreviewService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.web.SiteContentPolicy;
import com.code.pal.shared.web.SitesOrigin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Serves sites on their own hosts ({@link SitesOrigin}, host-per-site mode): {@code <slug>.<domain>}
 * is a published site, {@code pv--<key>.<domain>} a project's private preview and {@code dr--<key>.<domain>}
 * the draft of its running build. Such a host serves only its site, at the root, for every path: a request
 * there never reaches the API, sign-in or WebSocket endpoints, so the generated code can never call them
 * on its own origin.
 *
 * <p>Runs before every other filter, Spring Security included.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 4)
@RequiredArgsConstructor
public class SiteHostFilter extends OncePerRequestFilter {
    private final SitesOrigin sites;
    private final SiteContentPolicy policy;
    private final ProjectService projects;
    private final PreviewService preview;
    private final DraftPreviewService drafts;
    private final com.code.pal.agent.runtime.AppServerService servers;
    private final com.code.pal.agent.preview.PreviewGrants grants;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        String label = sites.siteLabel(request);
        if (label == null) {
            // The sites domain itself, a nested host or another port: never the API.
            if (sites.underWildcardDomain(request)) response.setStatus(HttpStatus.NOT_FOUND.value());
            else chain.doFilter(request, response);
            return;
        }
        response.setHeader("Content-Security-Policy", policy.header());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader(HttpHeaders.CACHE_CONTROL,"no-store");
        // Apply before generated-server proxying as well as static/draft serving.
        try {
            if (com.code.pal.project.brief.ProjectBriefFile.privatePath(path(request))) {
                response.setStatus(HttpStatus.NOT_FOUND.value()); return;
            }
        } catch (IllegalArgumentException invalidPath) { response.setStatus(HttpStatus.NOT_FOUND.value()); return; }
        // A MERN project's API: every method, forwarded to its server.
        if (isApi(request.getRequestURI())) {
            // A draft has no candidate API lease; a published static release has no API.
            // Never silently route either to the current saved project's server.
            if (!label.startsWith(SitesOrigin.PREVIEW_LABEL)) {
                response.setStatus(label.startsWith(SitesOrigin.DRAFT_LABEL) ? 503 : 404);
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
                return;
            }
            UUID projectId;
            try {
                projectId = project(label);
            } catch (RuntimeException notFound) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                return;
            }
            if (servers.hasServer(projectId)) {
                servers.proxy(projectId, request, response);
                return;
            }
            response.setStatus(404);response.setContentType("application/json");response.getWriter().write("{\"error\":\"Application API is unavailable\"}");return;
        }
        if (!"GET".equals(request.getMethod()) && !"HEAD".equals(request.getMethod())) {
            response.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
            return;
        }
        PreviewService.PreviewFile file;
        try {
            file = serve(label, path(request));
        } catch (RuntimeException notFound) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        response.setStatus(HttpStatus.OK.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, file.contentType());
        response.setHeader(HttpHeaders.CACHE_CONTROL, label.startsWith(SitesOrigin.PREVIEW_LABEL) || label.startsWith(SitesOrigin.DRAFT_LABEL)
                ? "no-store" : "no-cache");
        response.setContentLength(file.content().length);
        if (!"HEAD".equals(request.getMethod())) response.getOutputStream().write(file.content());
    }

    private PreviewService.PreviewFile serve(String label, String path) {
        if (label.startsWith(SitesOrigin.PREVIEW_LABEL)) {
            return preview.serve(privateGrant(label).projectId(), path);
        }
        if (label.startsWith(SitesOrigin.DRAFT_LABEL)) {
            var grant=privateGrant(label);UUID projectId=grant.projectId();
            String site = drafts.draftSite(projectId,grant.draftRunId());
            if (site == null) throw new IllegalStateException("No draft");
            return preview.serveSite(projectId, site, path);
        }
        return preview.servePublished(projects.resolvePublishedProject(label), path);
    }

    private UUID project(String label) {
        if (label.startsWith(SitesOrigin.PREVIEW_LABEL) || label.startsWith(SitesOrigin.DRAFT_LABEL)) return privateGrant(label).projectId();
        return projects.resolvePublishedProject(label);
    }
    private com.code.pal.agent.preview.PreviewGrants.Grant privateGrant(String label) {
        boolean draft=label.startsWith(SitesOrigin.DRAFT_LABEL);
        var grant=grants.resolve(label.substring(draft?SitesOrigin.DRAFT_LABEL.length():SitesOrigin.PREVIEW_LABEL.length()));
        if(draft!=(grant.draftRunId()!=null))throw new IllegalStateException("Preview mode changed");return grant;
    }

    private static boolean isApi(String uri) {
        return uri.equals("/api") || uri.startsWith("/api/");
    }

    private static String path(HttpServletRequest request) {
        String path = org.springframework.web.util.UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        while (path.startsWith("/")) path = path.substring(1);
        return path.isBlank() ? "index.html" : path;
    }
}
