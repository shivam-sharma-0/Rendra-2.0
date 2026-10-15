package com.code.pal.realtime;
import com.code.pal.identity.security.AuthSessions;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.realtime.security.JwtHandshakeInterceptor;
import com.code.pal.realtime.dto.RealtimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RealtimeRevocationTest {
    @Test
    void sweepRechecksMembershipSessionAndExpiry() throws Exception {
        UUID project = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        var actor = new AuthPrincipal(7L, UUID.randomUUID(), "fixture@example.invalid", "USER", sessionId);
        var auth = mock(AuthSessions.class);
        when(auth.active(sessionId, 7L)).thenReturn(true);
        var rights = mock(ProjectService.class);
        ObjectProvider<ProjectService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(rights);
        var service = new RealtimeService(new ObjectMapper(), mock(StringRedisTemplate.class), auth, provider);
        var socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("fixture");
        when(socket.isOpen()).thenReturn(true);
        var attributes = new HashMap<String, Object>();
        attributes.put(JwtHandshakeInterceptor.PRINCIPAL, actor);
        attributes.put(JwtHandshakeInterceptor.PROJECT_ID, project);
        attributes.put(JwtHandshakeInterceptor.EXPIRES_AT, Instant.now().plusSeconds(60));
        when(socket.getAttributes()).thenReturn(attributes);
        service.register(project, socket);
        service.deliverLocal(project, RealtimeMessage.of("INITIAL"));
        verify(socket).sendMessage(any());

        doThrow(new IllegalStateException("Membership revoked"))
                .when(rights).requireAccessible(7L, project);
        service.closeRevoked();
        service.deliverLocal(project, RealtimeMessage.of("PRIVATE"));
        verify(socket, times(1)).sendMessage(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);

        reset(rights);
        clearInvocations(socket);
        service.register(project, socket);
        when(auth.active(sessionId, 7L)).thenReturn(false);
        service.deliverLocal(project, RealtimeMessage.of("PRIVATE"));
        verify(socket, never()).sendMessage(any());
        verify(socket).close(CloseStatus.POLICY_VIOLATION);

        clearInvocations(socket);
        when(auth.active(sessionId, 7L)).thenReturn(true);
        attributes.put(JwtHandshakeInterceptor.EXPIRES_AT, Instant.now().minusSeconds(1));
        service.register(project, socket);
        service.closeRevoked();
        verify(socket).close(CloseStatus.POLICY_VIOLATION);
    }
}
