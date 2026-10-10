package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Stop ends a model call at once, whether it is waiting for the response or streaming it. */
class OpenAiCompatibleLlmClientCancelTest {
    private HttpServer server;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        timer.shutdownNow();
    }

    private OpenAiCompatibleLlmClient client() {
        AgentProperties props = new AgentProperties();
        props.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.getLlm().setApiKey("test");
        props.getLlm().setRequestTimeoutMs(30_000);
        props.getLlm().setStreamIdleTimeoutMs(30_000);
        return new OpenAiCompatibleLlmClient(props, new ObjectMapper());
    }

    /** Starts a call, cancels it after 300 ms, and returns how long the call took to end. */
    private long cancelledCallMillis() {
        OpenAiCompatibleLlmClient client = client();
        RunCancellation run = new RunCancellation(UUID.randomUUID());
        timer.schedule(run::cancel, 300, TimeUnit.MILLISECONDS);
        long start = System.nanoTime();
        try (RunCancellation.Registration ignored = RunCancellation.bind(run)) {
            assertThrows(LlmCancelledException.class, () -> client.chatStreaming(
                    new LlmClient.LlmRequest("test", List.of(LlmClient.LlmMessage.user("hi")), List.of(), 0.2), token -> { }));
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    @Test
    void stopsWhileWaitingForTheResponse() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        long elapsed = cancelledCallMillis();
        assertTrue(elapsed < 2_000, "took " + elapsed + " ms");
    }

    @Test
    void stopsMidStream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (Exception ignored) {
                // the client closed the stream
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        long elapsed = cancelledCallMillis();
        assertTrue(elapsed < 2_000, "took " + elapsed + " ms");
    }
}
