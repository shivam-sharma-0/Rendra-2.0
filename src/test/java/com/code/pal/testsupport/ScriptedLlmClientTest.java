package com.code.pal.testsupport;

import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedLlmClientTest {
    private final LlmClient.LlmRequest request = new LlmClient.LlmRequest("fake", List.of(), List.of(), 0);

    @Test void recordsKnownAndUnknownUsageAndRefusesExtraCalls() {
        var client = new ScriptedLlmClient().answer("known", new LlmClient.Usage(12, 3)).answer("unknown", null);
        StringBuilder streamed = new StringBuilder();
        assertEquals(12, client.chatStreaming(request, streamed::append).usage().promptTokens());
        assertEquals("known", streamed.toString());
        assertNull(client.chat(request).usage());
        assertEquals(2, client.requests().size());
        assertThrows(AssertionError.class, () -> client.chat(request));
    }

    @Test void exposesHttpStatusWithoutNetwork() {
        var client = new ScriptedLlmClient().httpError(429).answer("retry", new LlmClient.Usage(1, 1));
        assertEquals(429, assertThrows(LlmHttpException.class, () -> client.chat(request)).status());
        assertEquals("retry", client.chat(request).content());
    }

    @Test void stopsAnInFlightCallAndDoesNotConsumeAnotherOutcome() throws Exception {
        var entered = new CountDownLatch(1);
        var cancellation = new RunCancellation(UUID.randomUUID());
        var client = new ScriptedLlmClient().waitForCancellation(entered, Duration.ofSeconds(5));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var call = executor.submit(() -> {
                try (var ignored = RunCancellation.bind(cancellation)) {
                    return assertThrows(LlmCancelledException.class, () -> client.chat(request));
                }
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                cancellation.cancel();
                assertNotNull(call.get(2, TimeUnit.SECONDS));
                try (var ignored = RunCancellation.bind(cancellation)) {
                    assertThrows(LlmCancelledException.class, () -> client.chat(request));
                }
                assertEquals(1, client.requests().size());
            } finally { cancellation.cancel(); }
        }
    }

    @Test void queuedFixtureDistinguishesRequestAttachmentActorAndSequence() {
        var fixture = QueuedRequestFixture.create(ProjectType.BUILD);
        assertEquals(fixture.requestA().getId(), fixture.assistantA().getReplyToId());
        assertNotEquals(fixture.requestA().getAuthorId(), fixture.requestB().getAuthorId());
        assertEquals(fixture.requestA().getCreatedAt(), fixture.requestB().getCreatedAt());
        assertTrue(fixture.requestA().getSeq() < fixture.requestB().getSeq());
        assertEquals(List.of("uploads/a.png"), fixture.requestA().attachmentPaths());
    }
}
