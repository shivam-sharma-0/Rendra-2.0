package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Retry policy and time budget of the streaming client, against a local fake provider. */
class OpenAiCompatibleLlmClientBudgetTest {

    private static final String OK_BODY = """
            data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

            data: [DONE]

            """;

    private HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();
    private final List<String> keysSeen = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void rateLimitRotatesToTheNextKeyWithoutBackingOff() throws Exception {
        start(exchange -> {
            String key = exchange.getRequestHeaders().getFirst("Authorization");
            if (key.endsWith("key-c")) ok(exchange); else status(exchange, 429);
        });
        // A 10 s backoff would fail the timing assertion if rotation waited between keys.
        OpenAiCompatibleLlmClient client = client(List.of("key-a", "key-b", "key-c"), 3, 10_000, 60_000);

        long started = System.nanoTime();
        var result = client.chatStreaming(request(null), token -> { });

        assertEquals("ok", result.content());
        assertEquals(List.of("Bearer key-a", "Bearer key-b", "Bearer key-c"), keysSeen);
        assertTrue(elapsedMs(started) < 5_000, "key rotation must not back off");
    }

    @Test
    void providerOutageIsRetriedMaxAttemptsTimesNotOncePerKey() throws Exception {
        start(exchange -> status(exchange, 503));
        OpenAiCompatibleLlmClient client = client(List.of("key-a", "key-b", "key-c"), 2, 1, 60_000);

        assertThrows(RetryableLlmException.class, () -> client.chatStreaming(request(null), token -> { }));

        assertEquals(2, attempts.get(), "another key cannot fix a provider outage");
    }

    @Test
    void streamThatStallsBeforeOutputIsRetried() throws Exception {
        start(exchange -> {
            if (attempts.get() == 1) stall(exchange, ""); else ok(exchange);
        });
        OpenAiCompatibleLlmClient client = client(List.of("key-a"), 3, 1, 300);

        var result = client.chatStreaming(request(null), token -> { });

        assertEquals("ok", result.content());
        assertEquals(2, attempts.get());
    }

    @Test
    void streamThatStallsAfterOutputFailsWithoutRetry() throws Exception {
        start(exchange -> stall(exchange, "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"));
        OpenAiCompatibleLlmClient client = client(List.of("key-a"), 3, 1, 300);
        StringBuilder seen = new StringBuilder();

        LlmStreamException failure = assertThrows(LlmStreamException.class, () -> client.chatStreaming(request(null), seen::append));

        assertEquals(LlmStreamException.Reason.INTERRUPTED_STREAM, failure.reason());
        assertEquals(null, failure.usage());
        assertTrue(failure.getMessage().contains("after partial output"), failure.getMessage());
        assertTrue(failure.getMessage().contains("stream stalled"), failure.getMessage());
        assertEquals("partial", seen.toString());
        assertEquals(1, attempts.get(), "visible text must never be streamed twice");
    }

    @Test
    void budgetBoundsTheWholeCallIncludingRetries() throws Exception {
        start(exchange -> {
            sleep(10_000);              // headers never arrive in time
            ok(exchange);
        });
        // Without a budget this would take maxAttempts x requestTimeout = 3 x 60 s.
        OpenAiCompatibleLlmClient client = client(List.of("key-a"), 3, 1, 60_000);

        long started = System.nanoTime();
        assertThrows(LlmException.class, () -> client.chatStreaming(request(Duration.ofMillis(1_500)), token -> { }));

        assertTrue(elapsedMs(started) < 4_000, "took " + elapsedMs(started) + " ms");
    }

    @Test
    void budgetAlsoBoundsAStreamThatKeepsTrickling() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 100; i++) {   // never idle long enough for the idle guard
                    exchange.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    sleep(100);
                }
            } catch (IOException closedByClient) {
                // expected once the client gives up
            }
            exchange.close();
        });
        OpenAiCompatibleLlmClient client = client(List.of("key-a"), 3, 1, 60_000);

        long started = System.nanoTime();
        assertThrows(LlmException.class, () -> client.chatStreaming(request(Duration.ofMillis(1_500)), token -> { }));

        assertTrue(elapsedMs(started) < 4_000, "took " + elapsedMs(started) + " ms");
    }

    @Test
    void aRejectedKeyIsSetAsideAndTheProvidersOtherKeysGoOn() throws Exception {
        start(exchange -> {
            if ("Bearer key-bad".equals(exchange.getRequestHeaders().getFirst("Authorization"))) status(exchange, 401);
            else ok(exchange);
        });
        OpenAiCompatibleLlmClient client = client(List.of("key-bad", "key-good"), 3, 1, 60_000);

        assertEquals("ok", client.chatStreaming(request(Duration.ofSeconds(10)), token -> { }).content());
        assertEquals("ok", client.chatStreaming(request(Duration.ofSeconds(10)), token -> { }).content());
        assertEquals(1, keysSeen.stream().filter("Bearer key-bad"::equals).count(), "the rejected key is not tried again");
    }

    @Test
    void everyKeyRejectedFailsAtOnce() throws Exception {
        start(exchange -> status(exchange, 401));
        OpenAiCompatibleLlmClient client = client(List.of("key-a", "key-b"), 3, 1, 60_000);

        LlmHttpException failure = assertThrows(LlmHttpException.class, () -> client.chatStreaming(request(Duration.ofSeconds(10)), token -> { }));
        assertEquals(401, failure.status());
        assertEquals(2, attempts.get(), "each key once, no back-off rounds");
    }

    @Test
    void aForbiddenModelIsReportedAsUnavailableForTheAccount() throws Exception {
        start(exchange -> status(exchange, 403));
        OpenAiCompatibleLlmClient client = client(List.of("key-a", "key-b"), 3, 1, 60_000);

        LlmHttpException failure = assertThrows(LlmHttpException.class, () -> client.chatStreaming(request(Duration.ofSeconds(10)), token -> { }));
        assertTrue(failure.modelUnavailable());
        assertEquals(1, attempts.get(), "a model the account cannot use is not retried with other keys");
    }

    private void start(HttpHandler behaviour) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            keysSeen.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            behaviour.handle(exchange);
        });
        server.start();
    }

    private OpenAiCompatibleLlmClient client(List<String> keys, int maxAttempts, long backoffMs, long idleTimeoutMs) {
        AgentProperties props = new AgentProperties();
        AgentProperties.Provider provider = new AgentProperties.Provider();
        provider.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        provider.setApiKeys(keys);
        props.getProviders().put("fake", provider);
        props.getLlm().setMaxAttempts(maxAttempts);
        props.getLlm().setRetryInitialBackoffMs(backoffMs);
        props.getLlm().setRetryMaxBackoffMs(backoffMs);
        props.getLlm().setStreamIdleTimeoutMs(idleTimeoutMs);
        return new OpenAiCompatibleLlmClient(props, new ObjectMapper());
    }

    private static LlmClient.LlmRequest request(Duration budget) {
        return new LlmClient.LlmRequest("fake", "model", List.of(LlmClient.LlmMessage.user("hi")), List.of(), 0.2, budget);
    }

    private static void ok(HttpExchange exchange) throws IOException {
        byte[] bytes = OK_BODY.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void status(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    /** Sends headers (and optionally some output), then goes silent. */
    private static void stall(HttpExchange exchange, String firstChunk) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        if (!firstChunk.isEmpty()) {
            exchange.getResponseBody().write(firstChunk.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }
        sleep(5_000);
        exchange.close();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static long elapsedMs(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000; }
}
