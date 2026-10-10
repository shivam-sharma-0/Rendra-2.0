package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenAiCompatibleLlmClientTest {
    private HttpServer server;

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    void retries429ThenBatchesSseTextAndAssemblesToolCalls() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(exchange, attempts.incrementAndGet()));
        server.start();

        AgentProperties props = new AgentProperties();
        props.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.getLlm().setApiKey("test");
        props.getLlm().setRetryInitialBackoffMs(1);
        props.getLlm().setRetryMaxBackoffMs(1);
        props.getLlm().setMaxAttempts(2);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, new ObjectMapper());

        StringBuilder tokens = new StringBuilder();
        var result = client.chatStreaming(new LlmClient.LlmRequest("test", List.of(LlmClient.LlmMessage.user("hi")), List.of(), 0.2), tokens::append);

        assertEquals(2, attempts.get());
        assertEquals("Hello world", tokens.toString());
        assertEquals("Hello world", result.content());
        assertEquals(1, result.toolCalls().size());
        assertEquals("write_file", result.toolCalls().getFirst().name());
        assertEquals("{\"path\":\"a.txt\"}", result.toolCalls().getFirst().arguments());
    }

    @Test
    void sendsTheOutputCapOnlyWhenSet() throws Exception {
        List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 2);
        });
        server.start();
        AgentProperties props = new AgentProperties();
        props.getLlm().setBaseUrl("http://localhost:" + server.getAddress().getPort());
        props.getLlm().setApiKey("test");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, new ObjectMapper());
        LlmClient.LlmRequest request = new LlmClient.LlmRequest("test", List.of(LlmClient.LlmMessage.user("hi")), List.of(), 0.2);

        client.chat(request.withMaxTokens(77));
        client.chat(request);

        assertEquals(true, bodies.get(0).contains("\"max_tokens\":77"), bodies.get(0));
        assertEquals(false, bodies.get(1).contains("max_tokens"), bodies.get(1));
    }

    private static void respond(HttpExchange exchange, int attempt) throws IOException {
        if (attempt == 1) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(429, -1);
            exchange.close();
            return;
        }
        String body = """
                data: {"choices":[{"delta":{"content":"Hello "},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"world","tool_calls":[{"index":0,"id":"call-1","function":{"name":"write_file","arguments":"{\\"path\\":\\""}}]},"finish_reason":null}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"a.txt\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
