package com.code.pal.shared.web;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Cookie-authorized mutations need an explicit allowed browser origin; bearer routes remain separate. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 9)
public final class CookieMutationOriginFilter extends OncePerRequestFilter {
    private static final Set<String> COOKIE_MUTATIONS = Set.of("/api/auth/refresh", "/api/auth/logout");
    private final Set<String> origins;

    public CookieMutationOriginFilter(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> origins) {
        this.origins = Set.copyOf(origins);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !COOKIE_MUTATIONS.contains(RateLimitFilter.normalizedPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || !origins.contains(origin)) {
            response.setStatus(403);
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"A trusted origin is required\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
