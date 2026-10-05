package com.code.pal.agent.web;

import com.code.pal.agent.preview.DraftPreviewService;
import com.code.pal.agent.preview.PreviewService;
import com.code.pal.project.exception.ProjectNotFoundException;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.web.SiteContentPolicy;
import com.code.pal.shared.web.SitesOrigin;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** A per-site host serves only its site, at the root, and never lets a request reach the API. */
class SiteHostFilterTest {
    private final UUID projectId = UUID.randomUUID();
    private final SitesOrigin sites = new SitesOrigin("http://127.0.0.1:8080", "sites.localhost");
    private final ProjectService projects = mock(ProjectService.class);
    private final PreviewService preview = mock(PreviewService.class);
    private final DraftPreviewService drafts = mock(DraftPreviewService.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final SiteHostFilter filter = new SiteHostFilter(sites,
            new SiteContentPolicy(List.of("http://localhost:5173"), ""), projects, preview, drafts);

    SiteHostFilterTest() {
        when(projects.resolvePreviewKey("abc123")).thenReturn(projectId);
        when(projects.resolvePreviewKey("unknown")).thenThrow(new ProjectNotFoundException());
        when(preview.serve(eq(projectId), any())).thenAnswer(call ->
                new PreviewService.PreviewFile(("preview:" + call.getArgument(1)).getBytes(StandardCharsets.UTF_8), "text/html"));
        when(projects.resolvePublishedProject("bakery-1a2b")).thenReturn(projectId);
        when(preview.servePublished(eq(projectId), any())).thenAnswer(call ->
                new PreviewService.PreviewFile(("live:" + call.getArgument(1)).getBytes(StandardCharsets.UTF_8), "text/html"));
    }

    @Test
    void aPreviewHostServesItsProjectAtTheRootForEveryPath() throws Exception {
        assertEquals("preview:index.html", body(get("pv--abc123.sites.localhost", "/")));
        assertEquals("preview:menu/today", body(get("pv--abc123.sites.localhost", "/menu/today")), "client-side routes reach the app");
        MockHttpServletResponse api = get("pv--abc123.sites.localhost", "/api/auth/refresh");
        assertEquals("preview:api/auth/refresh", api.getContentAsString(), "the API path is just a site path here");
        assertTrue(api.getHeader("Content-Security-Policy").contains("frame-ancestors 'self' http://localhost:5173"));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void publishedAndUnknownHosts() throws Exception {
        assertEquals("live:index.html", body(get("bakery-1a2b.sites.localhost", "/")));
        assertEquals(404, get("pv--unknown.sites.localhost", "/").getStatus());
        assertEquals(404, get("sites.localhost", "/api/projects").getStatus(), "the bare sites domain is not the API");
        assertEquals(404, get("a.b.sites.localhost", "/").getStatus(), "nested hosts are not sites");
        assertEquals(405, request("POST", "pv--abc123.sites.localhost", "/").getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void otherHostsPassThrough() throws Exception {
        get("localhost", "/api/projects");
        verify(chain).doFilter(any(), any());
        assertEquals("http://pv--abc123.sites.localhost:8080/", sites.previewHostUrl("abc123"));
        assertEquals("http://bakery-1a2b.sites.localhost:8080/", sites.publishedUrl("bakery-1a2b"));
    }

    private MockHttpServletResponse get(String host, String path) throws Exception {
        return request("GET", host, path);
    }

    private MockHttpServletResponse request(String method, String host, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServerName(host);
        request.setServerPort(8080);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        assertEquals(200, response.getStatus());
        return response.getContentAsString();
    }
}
