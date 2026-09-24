package com.code.pal.realtime.security;

import com.code.pal.identity.security.JwtService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.exception.ApiException;
import com.code.pal.shared.security.AuthPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.UUID;

/**
 * Authenticates the WebSocket handshake from a {@code ?token=<accessToken>} query param.
 *
 * <p>A browser cannot set the {@code Authorization} header on a WebSocket handshake, so the
 * access token rides the URL. It is verified with the same {@link JwtService} as the REST filter,
 * then the caller's access to the requested {@code projectId} room is checked (workspace
 * membership). On success the principal + project are stashed in the session attributes; on any
 * failure the handshake is rejected and the socket never opens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    /** Session-attribute keys. */
    public static final String PRINCIPAL = "principal";
    public static final String PROJECT_ID = "projectId";

    private final JwtService jwtService;
    private final ProjectService projectService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        var query = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
        String token = query.getFirst("token");
        String projectIdRaw = query.getFirst("projectId");

        if (token == null || token.isBlank()) {
            return reject(response, "missing token");
        }

        final AuthPrincipal principal;
        try {
            Claims claims = jwtService.parse(token);
            if (!JwtService.ACCESS.equals(jwtService.tokenType(claims))) {
                return reject(response, "not an access token");
            }
            principal = jwtService.toPrincipal(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return reject(response, "invalid token: " + e.getMessage());
        }

        final UUID projectId;
        try {
            projectId = UUID.fromString(projectIdRaw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return reject(response, "missing/invalid projectId");
        }

        // Only members of the project's workspace may join its room.
        try {
            projectService.requireAccessible(principal.userId(), projectId);
        } catch (ApiException e) {
            return reject(response, "no access to project " + projectId);
        }

        attributes.put(PRINCIPAL, principal);
        attributes.put(PROJECT_ID, projectId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        log.debug("Realtime handshake rejected: {}", reason);
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }
}
