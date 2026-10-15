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
import java.util.Set;

/**
 * Distributed, atomic rate limiter for the sensitive auth mutations
 * ({@code POST /api/auth/login|signup|refresh}). Uses a Redis fixed-window counter keyed by
 * client IP so the limit holds across instances.
 *
 * <p>Runs before Spring Security so throttling happens before any credential work. If Redis is
 * unavailable it refuses authentication mutations by default; operators can explicitly restore
 * legacy fail-open behavior. Forwarded client identity requires configured trusted proxy hops.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> THROTTLED_PATHS =
            Set.of("/api/auth/login", "/api/auth/signup", "/api/auth/refresh");
    private static final String KEY_PREFIX = "rl:auth:";
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> WINDOW=new org.springframework.data.redis.core.script.DefaultRedisScript<>(
            "local n=redis.call('INCR',KEYS[1]); if n==1 or redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n;",Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !props.isEnabled()
                || !"POST".equalsIgnoreCase(request.getMethod())
                || !THROTTLED_PATHS.contains(normalizedPath(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final boolean allowed;
        try{allowed=allow(clientIp(request));}
        catch(RuntimeException unavailable){
            log.warn("Authentication rate-limit storage unavailable");
            if(props.isFailOpen()){chain.doFilter(request,response);return;}
            response.setStatus(503);response.setContentType(MediaType.APPLICATION_JSON_VALUE);response.setHeader(HttpHeaders.RETRY_AFTER,"5");response.getWriter().write("{\"message\":\"Sign-in limits are temporarily unavailable. Please try again shortly.\"}");return;
        }
        if(allowed)chain.doFilter(request,response);else writeTooManyRequests(request,response);
    }

    /** Atomically increment and attach or repair the expiry; storage errors reach the caller. */
    private boolean allow(String ip) {
        String key = KEY_PREFIX + ip;
        Long count=redis.execute(WINDOW,java.util.List.of(key),String.valueOf(Math.max(1,props.getAuthWindowSeconds())));
        if(count==null)throw new IllegalStateException("Rate-limit result missing");return count<=Math.max(1,props.getAuthRequests());
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(props.getAuthWindowSeconds()));
        ApiError body = ApiError.of(HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Please wait a moment and try again.", request.getRequestURI());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** Only an explicitly trusted immediate proxy can supply a forwarded chain. */
    private String clientIp(HttpServletRequest request) {
        String remote=request.getRemoteAddr();if(!props.getTrustedProxyIps().contains(remote))return remote;
        String forwarded = request.getHeader("X-Forwarded-For");
        if(forwarded!=null && forwarded.length()<=1024) {
            String[] chain=forwarded.split(",");for(int i=chain.length-1;i>=0;i--){String candidate=chain[i].trim();if(!literalIp(candidate))return remote;if(!props.getTrustedProxyIps().contains(candidate))return candidate;}
        }
        return remote;
    }
    private static boolean literalIp(String ip){
        if(ip.contains(":")){if(!ip.matches("[a-fA-F0-9:]{2,39}"))return false;try{java.net.InetAddress.getByName(ip);return true;}catch(java.net.UnknownHostException invalid){return false;}}
        if(!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;for(String part:ip.split("\\."))if(Integer.parseInt(part)>255)return false;return true;
    }
    private static String normalizedPath(HttpServletRequest request){try{return org.springframework.web.util.UriUtils.decode(request.getRequestURI(),java.nio.charset.StandardCharsets.UTF_8).replaceAll(";[^/]*","");}catch(IllegalArgumentException invalid){return "";}}
}
