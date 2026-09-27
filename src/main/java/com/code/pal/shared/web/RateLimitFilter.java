package com.code.pal.shared.web;

import com.code.pal.shared.exception.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Distributed, fail-open rate limiter for the sensitive auth mutations
 * ({@code POST /api/auth/login|signup|refresh}). Uses a Redis fixed-window counter keyed by
 * client IP so the limit holds across instances.
 *
 * <p>Runs before Spring Security so throttling happens before any credential work. If Redis is
 * unavailable it fails open (allows the request) — availability of login is preferred over a hard
 * dependency on the limiter, and the auth logic itself remains safe.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> THROTTLED_PATHS =
            Set.of("/api/auth/login", "/api/auth/signup", "/api/auth/refresh");
    private static final String KEY_PREFIX = "rl:auth:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !THROTTLED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (allow(clientIp(request))) {
            chain.doFilter(request, response);
        } else {
            writeTooManyRequests(request, response);
        }
    }

    /** Increment the window counter; first hit sets the TTL. Fail-open on any Redis error. */
    private boolean allow(String ip) {
        String key = KEY_PREFIX + ip;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofSeconds(props.getAuthWindowSeconds()));
            }
            return count == null || count <= props.getAuthRequests();
        } catch (RuntimeException ex) {
            log.warn("Rate limiter unavailable — allowing request: {}", ex.getMessage());
            return true;
        }
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(props.getAuthWindowSeconds()));
        ApiError body = ApiError.of(HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Please wait a moment and try again.", request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** Prefer the first hop in X-Forwarded-For (behind a trusted proxy), else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
