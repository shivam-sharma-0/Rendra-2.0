package com.code.pal.shared.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserContentOriginFilterTest {

    private final UserContentOriginFilter filter =
            new UserContentOriginFilter(new SitesOrigin("http://127.0.0.1:8080/"));

    @Test
    void apiHostRedirectsPublishedSitesToTheSitesOrigin() throws Exception {
        MockHttpServletResponse response = run("localhost", 8080, "/p/my-app/assets/app.js", "v=2");

        assertEquals(308, response.getStatus());
        assertEquals("http://127.0.0.1:8080/p/my-app/assets/app.js?v=2", response.getHeader("Location"));
    }

    @Test
    void apiHostRedirectsPreviewsToTheSitesOrigin() throws Exception {
        MockHttpServletResponse response = run("localhost", 8080, "/preview/token-123/index.html", null);

        assertEquals(308, response.getStatus());
        assertEquals("http://127.0.0.1:8080/preview/token-123/index.html", response.getHeader("Location"));
    }

    @Test
    void sitesHostRefusesTheTokenRefreshEndpoint() throws Exception {
        // The account-takeover path: generated JS calling refresh on its own origin.
        MockHttpServletResponse response = run("127.0.0.1", 8080, "/api/auth/refresh", null);

        assertEquals(404, response.getStatus());
    }

    @Test
    void sitesHostRefusesEverythingThatIsNotASite() throws Exception {
        for (String path : new String[] {"/api/projects", "/realtime", "/oauth2/authorization/google", "/actuator/health", "/px", "/"}) {
            assertEquals(404, run("127.0.0.1", 8080, path, null).getStatus(), path);
        }
    }

    @Test
    void sitesHostServesSites() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("127.0.0.1", 8080, "/p/my-app", null), new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest(), "request must continue to the site controller");
    }

    @Test
    void apiHostServesTheApi() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("localhost", 8080, "/api/projects", null), response, chain);
        assertNotNull(chain.getRequest());
        assertNull(response.getHeader("Location"));
    }

    @Test
    void dotSegmentsCannotDisguiseAnApiPathAsASite() throws Exception {
        // Tomcat dispatches "/p/../api/auth/refresh" as "/api/auth/refresh".
        MockHttpServletRequest request = request("127.0.0.1", 8080, "/p/../api/auth/refresh", null);
        request.setServletPath("/api/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(404, response.getStatus());
        assertNull(chain.getRequest(), "must not reach the API");
    }

    @Test
    void dotSegmentsCannotSneakASiteOntoTheApiOrigin() throws Exception {
        // Tomcat dispatches "/api/../p/my-app" as "/p/my-app": it must leave the API origin.
        MockHttpServletRequest request = request("localhost", 8080, "/api/../p/my-app", null);
        request.setServletPath("/p/my-app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(308, response.getStatus());
        assertNull(chain.getRequest(), "must not be served on the API origin");
    }

    @Test
    void samePortDifferentHostIsTheApiNotTheSitesOrigin() throws Exception {
        // Only the configured host:port is the sites origin; a mismatched port is treated as the API.
        assertEquals(308, run("127.0.0.1", 9090, "/p/my-app", null).getStatus());
    }

    private MockHttpServletResponse run(String host, int port, String path, String query) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(host, port, path, query), response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String host, int port, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServerName(host);
        request.setServerPort(port);
        request.setQueryString(query);
        return request;
    }
}
