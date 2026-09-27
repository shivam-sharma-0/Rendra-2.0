package com.code.pal.message;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide monotonic counter used only to break {@code createdAt} ties between messages created
 * moments apart (a user turn and its assistant placeholder). Ordering always compares createdAt first,
 * so a counter that resets on restart is still safe — it only ever needs to order rows created within
 * the same JVM lifetime against each other.
 */
@Component
public class MessageSequence {

    private final AtomicLong counter = new AtomicLong();

    public long next() {
        return counter.incrementAndGet();
    }
}
