package com.code.pal.realtime;

import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.realtime.security.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Lifecycle for the {@code /realtime} socket. By the time a method here runs the handshake
 * has already been authenticated by {@link JwtHandshakeInterceptor}, so the principal is
 * always present.
 *
 * <p>The handler is deliberately thin: connection bookkeeping is delegated to
 * {@link RealtimeService} (which every other module uses to push events), and inbound frames
 * only need to answer heartbeats — this is a server→client stream, not a chat protocol.
 */
@Slf4j
@RequiredArgsConstructor
public class RealtimeHandler extends TextWebSocketHandler {

    private static final int MAX_INBOUND_BYTES = 8 * 1024;   // clients only send tiny PINGs

    private final RealtimeService realtimeService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_INBOUND_BYTES);
        realtimeService.register(projectId(session), session);
        realtimeService.send(session, RealtimeMessage.of("CONNECTED"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Only heartbeat is understood inbound; everything else is ignored (thin stream channel).
        try {
            Map<?, ?> frame = objectMapper.readValue(message.getPayload(), Map.class);
            if ("PING".equals(frame.get("type"))) {
                realtimeService.send(session, RealtimeMessage.of("PONG"));
            }
        } catch (Exception e) {
            log.debug("Realtime ignored unparseable inbound frame: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        realtimeService.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Realtime transport error session={}: {}", session.getId(), exception.getMessage());
        realtimeService.unregister(session);
    }

    private static UUID projectId(WebSocketSession session) {
        return (UUID) session.getAttributes().get(JwtHandshakeInterceptor.PROJECT_ID);
    }
}
