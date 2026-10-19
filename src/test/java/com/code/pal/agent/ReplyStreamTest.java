package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReplyStreamTest {
    @Test
    void tinyProviderChunksPreserveTextWhileBoundingLiveAndDurableWrites() {
        var properties = properties();
        properties.setStreamBatchChars(20);
        properties.setStreamPersistChars(100);
        var started = new AtomicInteger();
        var deltas = new ArrayList<String>();
        var checkpoints = new ArrayList<String>();
        var finalReplies = new ArrayList<String>();
        var stream = new ReplyStream(properties, started::incrementAndGet, deltas::add, checkpoints::add, finalReplies::add);
        String answer = "A streamed reply. ".repeat(100);
        answer.codePoints().forEach(code -> stream.append(Character.toString(code)));
        stream.complete();

        assertEquals(1, started.get());
        assertEquals(answer, String.join("", deltas));
        assertEquals(answer, stream.content());
        assertEquals(List.of(answer), finalReplies);
        assertTrue(checkpoints.size() < answer.length() / 50, "Durable writes must not track provider chunks");
        assertTrue(deltas.size() > checkpoints.size());
        assertTrue(checkpoints.stream().allMatch(answer::startsWith));
    }

    @Test
    void completionFlushesTheLastSmallChunkAndPersistsTheWholeReply() {
        var properties = properties();
        properties.setStreamBatchChars(10_000);
        properties.setStreamPersistChars(10_000);
        var deltas = new ArrayList<String>();
        var checkpoints = new ArrayList<String>();
        var finalReplies = new ArrayList<String>();
        var stream = new ReplyStream(properties, () -> {}, deltas::add, checkpoints::add, finalReplies::add);
        stream.append(null);
        stream.append("");
        stream.append("A complete reply.");
        assertTrue(deltas.isEmpty());
        stream.complete();
        assertEquals(List.of("A complete reply."), deltas);
        assertEquals(deltas, finalReplies);
        assertTrue(checkpoints.isEmpty());
    }

    private AgentProperties properties() {
        var properties = new AgentProperties();
        properties.setStreamBatchIntervalMs(60_000);
        properties.setStreamPersistIntervalMs(60_000);
        return properties;
    }
}
