package com.code.pal.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns (or propagates) a per-request correlation id and makes it available for the whole
 * request lifecycle:
 * <ul>
 *   <li>put into the SLF4J {@link MDC} so every log line for the request carries it,</li>
 *   <li>echoed back in the {@code X-Request-Id} response header so clients/proxies can
 *       reference the same id in bug reports.</li>
 * </ul>
 *
 * <p>Runs first in the servlet chain (before Spring Security) so even auth failures are traceable.
 * A client-supplied id is honoured only when short and safe, to avoid log-injection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = sanitize(request.getHeader(ApiConstants.REQUEST_ID_HEADER));
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put(ApiConstants.REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(ApiConstants.REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ApiConstants.REQUEST_ID_MDC_KEY);
        }
    }

    /** Accept only short, character-safe ids from clients; otherwise mint our own. */
    private static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64 || !trimmed.matches("[A-Za-z0-9._-]+")) {
            return null;
        }
        return trimmed;
    }
}
