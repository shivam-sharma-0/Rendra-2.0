package com.code.pal.realtime;

import com.code.pal.identity.security.AuthSessions;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.realtime.security.JwtHandshakeInterceptor;
import com.code.pal.shared.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RealtimeCachingTest {
    private final UUID project = UUID.randomUUID();
    private final UUID family = UUID.randomUUID();
    private final AuthSessions auth = mock(AuthSessions.class);
    private final ProjectService rights = mock(ProjectService.class);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nanos = new AtomicLong();
    private RealtimeService service;

    @BeforeEach
    void connect() {
        ObjectProvider<ProjectService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(rights);
        service = new RealtimeService(mapper, mock(StringRedisTemplate.class), auth, provider, nanos::get);
        when(auth.active(family, 7L)).thenReturn(true);
        when(socket.getId()).thenReturn("fixture");
        when(socket.isOpen()).thenReturn(true);
        var attributes = new HashMap<String, Object>();
        attributes.put(JwtHandshakeInterceptor.PRINCIPAL,
                new AuthPrincipal(7L, UUID.randomUUID(), "fixture@example.invalid", "USER", family));
        attributes.put(JwtHandshakeInterceptor.PROJECT_ID, project);
        attributes.put(JwtHandshakeInterceptor.EXPIRES_AT, Instant.now().plusSeconds(60));
        when(socket.getAttributes()).thenReturn(attributes);
        service.register(project, socket);
    }

    @Test
    void aStreamSharesOneAuthorizationCheckWithinFiveSeconds() {
        for (int index = 0; index < 10; index++) {
            service.deliverLocal(project, RealtimeMessage.of("DELTA"));
        }
        verify(auth).active(family, 7L);
        verify(rights).requireAccessible(7L, project);
    }

    @Test
    void heartbeatResponsesDoNotQuerySessionOrMembership() {
        for (int index = 0; index < 10; index++) {
            service.send(socket, RealtimeMessage.of("PONG"));
        }
        verifyNoInteractions(auth, rights);
    }

    @Test
    void inboundHeartbeatFloodClosesTheSocketAfterTwentyFrames() throws Exception {
        var handler = new RealtimeHandler(service, mapper);
        for (int index = 0; index < 21; index++) {
            handler.handleTextMessage(socket, new TextMessage("{\"type\":\"PING\"}"));
        }
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verify(socket, times(20)).sendMessage(any());
        verifyNoInteractions(auth, rights);
    }

    @Test
    void revokedSessionStopsAtTheFiveSecondBoundary() throws Exception {
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        when(auth.active(family, 7L)).thenReturn(false);
        nanos.set(4_999_999_999L);
        service.deliverLocal(project, RealtimeMessage.of("CACHED"));
        nanos.set(5_000_000_000L);
        service.deliverLocal(project, RealtimeMessage.of("PRIVATE"));

        verify(auth, times(2)).active(family, 7L);
        verify(socket, times(2)).sendMessage(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        assertFalse(service.allowInbound(socket));
    }

    @Test
    void revokedMembershipStopsAtTheFiveSecondBoundary() throws Exception {
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        doThrow(new IllegalStateException("Membership revoked"))
                .when(rights).requireAccessible(7L, project);
        nanos.set(5_000_000_000L);
        service.deliverLocal(project, RealtimeMessage.of("PRIVATE"));

        verify(rights, times(2)).requireAccessible(7L, project);
        verify(socket).sendMessage(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void expiredTokenCannotUseCachedAuthorization() throws Exception {
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        socket.getAttributes().put(JwtHandshakeInterceptor.EXPIRES_AT, Instant.now().minusSeconds(1));
        service.deliverLocal(project, RealtimeMessage.of("PRIVATE"));

        verify(auth).active(family, 7L);
        verify(socket).sendMessage(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void scheduledSweepRechecksEvenFreshCachedAuthorization() throws Exception {
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        when(auth.active(family, 7L)).thenReturn(false);
        service.closeRevoked();

        verify(auth, times(2)).active(family, 7L);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void inboundAllowanceResetsAfterOneSecond() {
        for (int index = 0; index < 20; index++) {
            assertTrue(service.allowInbound(socket));
        }
        nanos.set(1_000_000_000L);
        assertTrue(service.allowInbound(socket));
        verifyNoInteractions(auth, rights);
    }

    @Test
    void malformedFramesAlsoConsumeTheInboundAllowance() throws Exception {
        var handler = new RealtimeHandler(service, mapper);
        for (int index = 0; index < 21; index++) {
            handler.handleTextMessage(socket, new TextMessage("invalid-json"));
        }
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(auth, rights);
    }

    @Test
    void unregisterDropsAuthorizationCacheAndFrameCounters() {
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        for (int index = 0; index < 20; index++) {
            assertTrue(service.allowInbound(socket));
        }
        service.unregister(socket);
        service.register(project, socket);
        assertTrue(service.allowInbound(socket));
        service.deliverLocal(project, RealtimeMessage.of("RECONNECTED"));
        verify(auth, times(2)).active(family, 7L);
    }

    @Test
    void broadcastPongPayloadCannotBypassAuthorization() throws Exception {
        when(auth.active(family, 7L)).thenReturn(false);
        service.deliverLocal(project, RealtimeMessage.of("PONG", "private payload"));
        verify(auth).active(family, 7L);
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }
}
