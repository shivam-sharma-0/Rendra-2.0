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
    private final com.code.pal.agent.runtime.AppServerService servers = mock(com.code.pal.agent.runtime.AppServerService.class);
    private final com.code.pal.agent.preview.PreviewGrants grants=mock(com.code.pal.agent.preview.PreviewGrants.class);
    private final SiteHostFilter filter = new SiteHostFilter(sites,
            new SiteContentPolicy(List.of("http://localhost:5173"), ""), projects, preview, drafts, servers,grants);

    SiteHostFilterTest() {
        when(projects.resolvePreviewKey("abc123")).thenReturn(projectId);
        when(projects.resolvePreviewKey("unknown")).thenThrow(new ProjectNotFoundException());
        when(grants.resolve("abc123")).thenReturn(new com.code.pal.agent.preview.PreviewGrants.Grant(UUID.randomUUID(),projectId,7L,UUID.randomUUID(),null,java.time.Instant.now().plusSeconds(60)));
        when(grants.resolve("unknown")).thenThrow(new ProjectNotFoundException());
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
        assertEquals(404,api.getStatus());assertEquals("application/json",api.getContentType());assertFalse(api.getContentAsString().contains("preview:"),"API routes never receive SPA HTML");
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

    @Test void privateBriefRequestsNeverReachStaticServingOrGeneratedServer() throws Exception {
        when(servers.hasServer(projectId)).thenReturn(true);
        for(String host:List.of("pv--abc123.sites.localhost","dr--abc123.sites.localhost","bakery-1a2b.sites.localhost"))
            for(String path:List.of("/RENDRA.md","/nested/%52ENDRA.md","/api/RENDRA.md","/RENDRA.md.bak")) {
                assertEquals(404,request("GET",host,path).getStatus());
                assertEquals(404,request("HEAD",host,path).getStatus());
            }
        verify(servers,never()).proxy(any(),any(),any());
        verify(preview,never()).serve(any(),any());verify(preview,never()).servePublished(any(),any());verify(preview,never()).serveSite(any(),any(),any());
    }

    @Test void draftAndPublishedApiNeverReachTheSavedServer() throws Exception {
        when(servers.hasServer(projectId)).thenReturn(true);
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            assertEquals(503, request(method, "dr--abc123.sites.localhost", "/api/items").getStatus());
            assertEquals(404, request(method, "bakery-1a2b.sites.localhost", "/api/items").getStatus());
        }
        verify(servers, never()).proxy(any(), any(), any());
        assertEquals(200, request("GET", "pv--abc123.sites.localhost", "/api/items").getStatus());
        verify(servers).proxy(eq(projectId), any(), any());
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
