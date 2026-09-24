package com.code.pal.realtime;

import com.code.pal.realtime.dto.RealtimeEvent;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds the live connections and fans events out to them. Other modules inject this to stream
 * to a user (e.g. {@code sendToUser(ownerId, RealtimeMessage.of("BUILD_LOG", ...))}) without
 * knowing anything about sockets — or about which instance the socket lives on.
 *
 * <p><b>Rooms</b>: sockets are grouped by {@code projectId} — everyone viewing a project shares a
 * room, so a message or agent token reaches all collaborators, not just the sender.
 *
 * <p><b>Horizontal scale</b>: the room registry is intentionally per-instance (each node only
 * tracks its own connections). {@link #sendToProject} therefore does not deliver directly — it
 * <em>publishes</em> to a Redis channel, and every instance's subscriber calls {@link #deliverLocal}
 * for whichever of that room's sockets it holds. So an event produced on node A reaches a member's
 * socket on node B. With one node it still works (the node receives its own publish).
 *
 * <p><b>Registry</b>: {@code projectId → sessions} for O(1) room fan-out, plus
 * {@code sessionId → projectId} for O(1) cleanup on disconnect. Both are concurrent maps.
 *
 * <p><b>Thread-safe writes</b>: each session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}
 * so concurrent {@code sendMessage} calls are serialised (a raw session throws on that).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealtimeService {

    /** Redis Pub/Sub channel every instance subscribes to for realtime fan-out. */
    public static final String CHANNEL = "realtime:events";

    /** Buffer bounds for the concurrent decorator — drop a slow client rather than leak memory. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    private final Map<UUID, Set<WebSocketSession>> sessionsByProject = new ConcurrentHashMap<>();
    private final Map<String, UUID> projectBySession = new ConcurrentHashMap<>();

    /** Add a freshly opened, already-authorized session to its project room. */
    public void register(UUID projectId, WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        sessionsByProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(session);
        projectBySession.put(raw.getId(), projectId);
        log.debug("Realtime join project={} session={} (room size={})", projectId, raw.getId(), count(projectId));
    }

    /** Drop a closed session; prunes the room when its last socket goes. */
    public void unregister(WebSocketSession raw) {
        UUID projectId = projectBySession.remove(raw.getId());
        if (projectId == null) return;
        sessionsByProject.computeIfPresent(projectId, (k, set) -> {
            set.removeIf(s -> s.getId().equals(raw.getId()));
            return set.isEmpty() ? null : set;
        });
        log.debug("Realtime leave project={} session={}", projectId, raw.getId());
    }

    /**
     * Broadcast an event to a project room across the whole cluster. Publishes to Redis; every
     * instance's subscriber then delivers to the room sockets it holds (see {@link #deliverLocal}).
     */
    public void sendToProject(UUID projectId, RealtimeMessage message) {
        try {
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(new RealtimeEvent(projectId, message)));
        } catch (JacksonException e) {
            log.warn("Realtime publish failed project={} type={}: {}", projectId, message.type(), e.getMessage());
        }
    }

    /** Deliver to this instance's own sockets in a project room. Called by the Redis subscriber. */
    public void deliverLocal(UUID projectId, RealtimeMessage message) {
        Set<WebSocketSession> sessions = sessionsByProject.get(projectId);
        if (sessions == null || sessions.isEmpty()) return;

        TextMessage frame = serialize(message);
        if (frame == null) return;
        for (WebSocketSession session : sessions) {
            deliver(session, frame);
        }
    }

    /** Send directly to one session (used by the handler for CONNECTED / PONG replies). */
    public void send(WebSocketSession session, RealtimeMessage message) {
        TextMessage frame = serialize(message);
        if (frame != null) deliver(session, frame);
    }

    private void deliver(WebSocketSession session, TextMessage frame) {
        try {
            if (session.isOpen()) session.sendMessage(frame);
        } catch (IOException | IllegalStateException e) {
            // Broken pipe / buffer overrun — close and let afterConnectionClosed clean up.
            log.debug("Realtime send failed session={}: {}", session.getId(), e.getMessage());
            try {
                session.close();
            } catch (IOException ignored) {
                // already gone
            }
        }
    }

    private TextMessage serialize(RealtimeMessage message) {
        try {
            return new TextMessage(objectMapper.writeValueAsString(message));
        } catch (JacksonException e) {
            log.warn("Realtime serialize failed type={}: {}", message.type(), e.getMessage());
            return null;
        }
    }

    private int count(UUID projectId) {
        Set<WebSocketSession> set = sessionsByProject.get(projectId);
        return set == null ? 0 : set.size();
    }
}
