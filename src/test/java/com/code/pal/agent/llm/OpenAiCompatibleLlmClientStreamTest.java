package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleLlmClientStreamTest {
    private HttpServer server;
    private final AtomicInteger attempts = new AtomicInteger();

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private OpenAiCompatibleLlmClient client(String contentType, String body) throws Exception {
        return client(200, contentType, body);
    }

    private OpenAiCompatibleLlmClient client(int status, String contentType, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        var properties = new AgentProperties();
        properties.getLlm().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getLlm().setApiKey("synthetic-test");
        properties.getLlm().setMaxAttempts(3);
        return new OpenAiCompatibleLlmClient(properties, new ObjectMapper());
    }

    private LlmClient.LlmRequest request() {
        return new LlmClient.LlmRequest("openrouter", "test", List.of(), List.of(), 0, Duration.ofSeconds(5));
    }

    @Test void planningUsesOneTransmissionBeforeTryingAnotherConfiguredRoute() throws Exception {
        var client = client(503, "application/json", "{}");
        try (var ignored = LlmCallContext.purpose(CallPurpose.PLANNER, true)) {
            assertThrows(RetryableLlmException.class, () -> client.chat(request()));
        }
        assertEquals(1, attempts.get());
    }

    @Test void unitCorrectionKeepsTheCodingTransportPolicy() throws Exception {
        var client = client(503, "application/json", "{}");
        try (var ignored = LlmCallContext.purpose(CallPurpose.UNIT_CORRECTION, true)) {
            assertThrows(RetryableLlmException.class, () -> client.chat(request()));
        }
        assertEquals(3, attempts.get());
    }

    @Test void http200ProviderErrorCannotBecomeAnEmptySuccessOrRetry() throws Exception {
        var client = client("text/event-stream", "data: {\"error\":{\"code\":503,\"message\":\"sensitive synthetic provider text\"}}\n\ndata: [DONE]\n\n");
        var failure = assertThrows(LlmStreamException.class, () -> client.chat(request()));
        assertEquals(LlmStreamException.Reason.PROVIDER_ERROR, failure.reason());
        assertEquals(503, failure.providerCode());
        assertNull(failure.usage());
        assertFalse(failure.getMessage().contains("sensitive"));
        assertEquals(1, attempts.get());
    }

    @Test void errorAfterPartialToolCallDiscardsOperationAndRetainsReportedUsage() throws Exception {
        var client = client("text/event-stream", "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"writeFile\",\"arguments\":\"{\"}}]}}]}\n\n"
                + "data: {\"usage\":{\"prompt_tokens\":31,\"completion_tokens\":7},\"error\":{\"code\":\"server_error\"},\"choices\":[{\"finish_reason\":\"error\"}]}\n\n");
        var failure = assertThrows(LlmStreamException.class, () -> client.chat(request()));
        assertEquals(new LlmClient.Usage(31, 7), failure.usage());
        assertNull(failure.providerCode());
        assertEquals(1, attempts.get());
    }

    @Test void finishErrorWithoutAnErrorObjectStillFails() throws Exception {
        var client = client("text/event-stream", "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"error\"}]}\n\ndata: [DONE]\n\n");
        assertThrows(LlmStreamException.class, () -> client.chat(request()));
    }

    @Test void emptyStreamDoesNotBecomeAnApprovedPlan() throws Exception {
        var client = client("text/event-stream", ": heartbeat\n\ndata: [DONE]\n\n");
        var failure = assertThrows(LlmStreamException.class, () -> client.chat(request()));
        assertEquals(LlmStreamException.Reason.EMPTY_STREAM, failure.reason());
    }

    @Test void nonStreamSuccessBodyIsRejectedWithoutLeakingIt() throws Exception {
        var client = client("application/json", "{\"error\":\"sensitive fixture data\"}");
        var failure = assertThrows(LlmStreamException.class, () -> client.chat(request()));
        assertEquals(LlmStreamException.Reason.UNEXPECTED_CONTENT_TYPE, failure.reason());
        assertFalse(failure.getMessage().contains("sensitive"));
    }
}
