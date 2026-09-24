package com.code.pal.realtime.config;

import com.code.pal.realtime.RealtimeHandler;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.security.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * Wires the raw WebSocket endpoint at {@code /realtime}.
 *
 * <p>The handshake is gated by {@link JwtHandshakeInterceptor} (token auth) and an origin
 * allow-list (defence in depth — the token is the real gate). No SockJS fallback: we target
 * modern browsers that all speak native WebSocket, keeping the client dependency-free.
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeService realtimeService;
    private final ObjectMapper objectMapper;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    /** Browser origins allowed to open the socket (comma-separated). Dev SPA by default. */
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new RealtimeHandler(realtimeService, objectMapper), "/realtime")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins(allowedOrigins);
    }
}
