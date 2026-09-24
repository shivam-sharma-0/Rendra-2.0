package com.code.pal.message.domain;

/**
 * Lifecycle of a message. User messages are born {@code COMPLETE}; an assistant reply starts
 * {@code PENDING}, flips to {@code STREAMING} as tokens arrive over {@code /realtime}, and ends
 * {@code COMPLETE} or {@code FAILED}.
 */
public enum MessageStatus {
    PENDING,
    STREAMING,
    COMPLETE,
    FAILED
}
