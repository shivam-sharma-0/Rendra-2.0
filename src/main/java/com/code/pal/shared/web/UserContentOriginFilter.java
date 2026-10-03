package com.code.pal.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

/**
 * Keeps untrusted, user-generated sites off the API origin. On the API origin a generated app's
 * JavaScript could POST to {@code /api/auth/refresh} with the viewer's refresh cookie and read the
 * new access token from the same-origin response — taking over the account of anyone who opens a
 * preview or published site.
 *
 * <ul>
 *   <li>Sites host: only {@code /preview/**} and {@code /p/**}; everything else (API, OAuth,
 *       WebSocket, actuator) is 404.</li>
 *   <li>API host: never serves those paths; they are redirected (308) to the sites host, so links
 *       stored before this split keep working.</li>
 * </ul>
 *
 * <p>Runs right after the correlation id and before rate limiting and Spring Security.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class UserContentOriginFilter extends OncePerRequestFilter {

    private final SitesOrigin sites;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean sitesRequest = sites.isSitesRequest(request);
        // The raw URI and the path the container actually dispatches (decoded, dot-segments removed)
        // can disagree, e.g. "/p/../api/auth/refresh" dispatches as "/api/auth/refresh". The sites
        // host serves a request only when BOTH look like a site; the API host hands a request off
        // to the sites host when EITHER does, so user content is never served on the API origin.
        boolean rawSite = SitesOrigin.isSitesPath(request.getRequestURI());
        boolean dispatchedSite = SitesOrigin.isSitesPath(dispatchPath(request));
        if (sitesRequest && !(rawSite && dispatchedSite)) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        if (!sitesRequest && (rawSite || dispatchedSite)) {
            String query = request.getQueryString();
            response.setStatus(HttpStatus.PERMANENT_REDIRECT.value());
            response.setHeader(HttpHeaders.LOCATION,
                    sites.resolve(request.getRequestURI()) + (query == null ? "" : "?" + query));
            return;
        }
        chain.doFilter(request, response);
    }

    private static String dispatchPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isEmpty() ? request.getRequestURI() : path;
    }
}
