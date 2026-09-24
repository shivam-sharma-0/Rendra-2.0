package com.code.pal.realtime.dto;

/**
 * Envelope for every frame on the {@code /realtime} socket, in both directions.
 *
 * <p>A single shape ({@code type} + free-form {@code payload}) lets one connection multiplex
 * many event kinds (build logs, chat tokens, deploy status) without a per-event class or a
 * message broker — the client switches on {@code type}.
 *
 * @param type    discriminator, e.g. {@code "CONNECTED"}, {@code "BUILD_LOG"}, {@code "PING"}
 * @param payload event data; {@code null} for signalling frames like PING
 */
public record RealtimeMessage(String type, Object payload) {

    public static RealtimeMessage of(String type, Object payload) {
        return new RealtimeMessage(type, payload);
    }

    public static RealtimeMessage of(String type) {
        return new RealtimeMessage(type, null);
    }
}
