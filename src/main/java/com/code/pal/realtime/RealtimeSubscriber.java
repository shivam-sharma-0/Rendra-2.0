package com.code.pal.realtime;

import com.code.pal.realtime.dto.RealtimeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Receives realtime events published on the Redis channel and hands them to the local
 * {@link RealtimeService} for delivery to whichever of the target user's sockets this
 * instance holds. Every instance (including the publisher) runs one of these.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeSubscriber implements MessageListener {

    private final RealtimeService realtimeService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RealtimeEvent event = objectMapper.readValue(message.getBody(), RealtimeEvent.class);
            realtimeService.deliverLocal(event.projectId(), event.message());
        } catch (Exception e) {
            log.warn("Realtime subscriber dropped a message: {}", e.getMessage());
        }
    }
}
