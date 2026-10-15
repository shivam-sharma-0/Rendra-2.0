package com.code.pal.realtime;

import com.code.pal.identity.security.AuthSessions;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.dto.RealtimeEvent;
import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.realtime.security.JwtHandshakeInterceptor;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

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
 * {@code sessionId → socket state} for O(1) cleanup on disconnect. Both are concurrent maps.
 *
 * <p><b>Thread-safe writes</b>: each session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}
 * so concurrent {@code sendMessage} calls are serialised (a raw session throws on that).
 */
@Slf4j
@Service
public class RealtimeService {

    /** Redis Pub/Sub channel every instance subscribes to for realtime fan-out. */
    public static final String CHANNEL = "realtime:events";

    /** Buffer bounds for the concurrent decorator — drop a slow client rather than leak memory. */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;
    private static final long AUTH_CACHE_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long INBOUND_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final int MAX_INBOUND_FRAMES = 20;

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final AuthSessions authSessions;
    private final ObjectProvider<ProjectService> projects;
    private final LongSupplier nanoTime;

    private final Map<UUID, Set<WebSocketSession>> sessionsByProject = new ConcurrentHashMap<>();
    private final Map<String, SocketState> stateBySession = new ConcurrentHashMap<>();

    @Autowired
    public RealtimeService(ObjectMapper objectMapper, StringRedisTemplate redis,
                           AuthSessions authSessions, ObjectProvider<ProjectService> projects) {
        this(objectMapper, redis, authSessions, projects, System::nanoTime);
    }

    RealtimeService(ObjectMapper objectMapper, StringRedisTemplate redis,
                    AuthSessions authSessions, ObjectProvider<ProjectService> projects,
                    LongSupplier nanoTime) {
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.authSessions = authSessions;
        this.projects = projects;
        this.nanoTime = nanoTime;
    }

    /** Add a freshly opened, already-authorized session to its project room. */
    public void register(UUID projectId, WebSocketSession raw) {
        unregister(raw);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                raw, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES);
        stateBySession.put(raw.getId(), new SocketState(projectId, session));
        sessionsByProject.computeIfAbsent(projectId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.debug("Realtime join project={} session={} (room size={})", projectId, raw.getId(), count(projectId));
    }

    /** Drop a closed session; prunes the room when its last socket goes. */
    public void unregister(WebSocketSession raw) {
        SocketState state = stateBySession.remove(raw.getId());
        if (state == null) {
            return;
        }
        UUID projectId = state.projectId;
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
            SocketState state = stateBySession.get(session.getId());
            if (state != null && state.session == session && state.projectId.equals(projectId)) {
                deliver(state, frame, false);
            }
        }
    }

    /** Send directly to one session (used by the handler for CONNECTED / PONG replies). */
    public void send(WebSocketSession session, RealtimeMessage message) {
        SocketState state = stateBySession.get(session.getId());
        if (state == null) {
            return;
        }
        TextMessage frame = serialize(message);
        if (frame != null) {
            boolean heartbeat = "PONG".equals(message.type()) && message.payload() == null;
            deliver(state, frame, heartbeat);
        }
    }

    private void deliver(SocketState state, TextMessage frame, boolean heartbeat) {
        WebSocketSession session = state.session;
        try {
            if (!heartbeat && !authorized(state, false)) {
                close(state, CloseStatus.POLICY_VIOLATION);
                return;
            }
            if (session.isOpen()) {
                session.sendMessage(frame);
            } else {
                unregister(session);
            }
        } catch (IOException | IllegalStateException e) {
            // Broken pipe / buffer overrun — close and let afterConnectionClosed clean up.
            log.debug("Realtime send failed session={}: {}", session.getId(), e.getMessage());
            close(state, CloseStatus.SERVER_ERROR);
        }
    }

    private boolean authorized(SocketState state, boolean force) {
        synchronized (state) {
            try {
                var attributes = state.session.getAttributes();
                var actor = (AuthPrincipal) attributes.get(JwtHandshakeInterceptor.PRINCIPAL);
                var expiry = (Instant) attributes.get(JwtHandshakeInterceptor.EXPIRES_AT);
                if (actor == null || expiry == null || !expiry.isAfter(Instant.now())) {
                    return false;
                }
                long now = nanoTime.getAsLong();
                if (!force && state.lastAuthorizedAt != null
                        && now - state.lastAuthorizedAt < AUTH_CACHE_NANOS) {
                    return true;
                }
                if (!authSessions.active(actor.sessionId(), actor.userId())) {
                    return false;
                }
                projects.getObject().requireAccessible(actor.userId(), state.projectId);
                state.lastAuthorizedAt = now;
                return true;
            } catch (RuntimeException revoked) {
                return false;
            }
        }
    }

    /** Bound all inbound text frames before parsing, including malformed JSON. */
    public boolean allowInbound(WebSocketSession session) {
        SocketState state = stateBySession.get(session.getId());
        if (state == null) {
            return false;
        }
        synchronized (state) {
            long now = nanoTime.getAsLong();
            if (state.inboundFrames == 0 || now - state.windowStartedAt >= INBOUND_WINDOW_NANOS) {
                state.windowStartedAt = now;
                state.inboundFrames = 0;
            }
            if (++state.inboundFrames <= MAX_INBOUND_FRAMES) {
                return true;
            }
        }
        close(state, CloseStatus.POLICY_VIOLATION);
        return false;
    }

    @Scheduled(fixedDelayString = "${app.realtime.recheck-ms:30000}")
    public void closeRevoked() {
        for (SocketState state : stateBySession.values()) {
            if (!authorized(state, true)) {
                close(state, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    private void close(SocketState state, CloseStatus status) {
        try {
            state.session.close(status);
        } catch (IOException ignored) {
            // The transport may already be gone; always drop its registry and cache entries.
        } finally {
            unregister(state.session);
        }
    }

    private static final class SocketState {
        private final UUID projectId;
        private final WebSocketSession session;
        private Long lastAuthorizedAt;
        private long windowStartedAt;
        private int inboundFrames;

        private SocketState(UUID projectId, WebSocketSession session) {
            this.projectId = projectId;
            this.session = session;
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
