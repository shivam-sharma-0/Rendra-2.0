package com.code.pal.realtime.dto;

import java.util.UUID;

/**
 * Envelope published on the Redis channel so any instance can deliver an event to every socket
 * in a project's room — wherever those sockets are connected.
 *
 * @param projectId the room (project) to broadcast to
 * @param message   the payload to deliver over the members' WebSockets
 */
public record RealtimeEvent(UUID projectId, RealtimeMessage message) {
}
