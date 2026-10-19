package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Coalesces provider chunks for live delivery and less frequent durable checkpoints. */
final class ReplyStream {
    private final AgentProperties properties;
    private final Runnable started;
    private final Consumer<String> broadcast;
    private final Consumer<String> checkpoint;
    private final Consumer<String> finished;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();
    private long lastFlushNanos = System.nanoTime();
    private long lastPersistNanos = System.nanoTime();
    private int persistedLength;

    ReplyStream(AgentProperties properties, Runnable started, Consumer<String> broadcast,
            Consumer<String> checkpoint, Consumer<String> finished) {
        this.properties = properties;
        this.started = started;
        this.broadcast = broadcast;
        this.checkpoint = checkpoint;
        this.finished = finished;
    }

    synchronized void append(String delta) {
        if (delta == null || delta.isEmpty()) return;
        if (content.isEmpty()) started.run();
        content.append(delta);
        pending.append(delta);
        if (pending.length() >= Math.max(1, properties.getStreamBatchChars())
                || elapsedMs(lastFlushNanos) >= properties.getStreamBatchIntervalMs()) flush();
    }

    synchronized void flush() {
        if (pending.isEmpty()) return;
        String delta = pending.toString();
        pending.setLength(0);
        lastFlushNanos = System.nanoTime();
        broadcast.accept(delta);
        if (content.length() - persistedLength >= Math.max(1, properties.getStreamPersistChars())
                || elapsedMs(lastPersistNanos) >= properties.getStreamPersistIntervalMs()) {
            persistedLength = content.length();
            lastPersistNanos = System.nanoTime();
            checkpoint.accept(content.toString());
        }
    }

    synchronized void complete() {
        flush();
        finished.accept(content.toString());
    }

    synchronized String content() {
        return content.toString();
    }

    private static long elapsedMs(long sinceNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
    }
}
