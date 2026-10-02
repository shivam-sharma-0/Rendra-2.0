package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI-compatible streaming client. It understands the standard SSE delta format used by
 * NVIDIA, OpenAI and OpenRouter, while assembling tool-call argument deltas into one final result.
 * A failed request is retried only before any text has been published, so users never see a
 * duplicated partial answer.
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final Map<String, Endpoint> endpoints;
    private final double defaultTemperature;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Duration requestTimeout;

    public OpenAiCompatibleLlmClient(AgentProperties props, ObjectMapper objectMapper) {
        AgentProperties.Llm llm = props.getLlm();
        this.objectMapper = objectMapper;
        this.defaultTemperature = llm.getTemperature();
        this.maxAttempts = Math.max(1, llm.getMaxAttempts());
        this.initialBackoffMs = Math.max(50, llm.getRetryInitialBackoffMs());
        this.maxBackoffMs = Math.max(initialBackoffMs, llm.getRetryMaxBackoffMs());
        this.requestTimeout = Duration.ofMillis(Math.max(1_000, llm.getRequestTimeoutMs()));
        Map<String, Endpoint> configured = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((name, provider) -> {
                if (provider != null && provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
                    configured.put(name.toLowerCase(java.util.Locale.ROOT),
                            new Endpoint(provider.getBaseUrl(), provider.getApiKey()));
                }
            });
        }
        // Preserve the old AGENT_LLM_BASE_URL / AGENT_LLM_API_KEY setup when no provider block
        // is configured. Existing deployments therefore keep working during the migration.
        if (!configured.containsKey("openrouter") && llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank()) {
            configured.put("openrouter", new Endpoint(llm.getBaseUrl(), llm.getApiKey()));
        }
        this.endpoints = Map.copyOf(configured);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta) {
        String provider = request.provider() == null || request.provider().isBlank()
                ? "openrouter" : request.provider().toLowerCase(java.util.Locale.ROOT);
        Endpoint endpoint = endpoints.get(provider);
        if (endpoint == null || endpoint.apiKey().isBlank()) {
            throw new LlmException("LLM provider is not configured: " + provider);
        }

        long backoffMs = initialBackoffMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return streamOnce(endpoint, request, onTextDelta);
            } catch (RetryableLlmException e) {
                if (attempt == maxAttempts) throw e;
                long base = e.retryAfterMs() > 0 ? e.retryAfterMs() : backoffMs;
                long delay = withJitter(base);
                log.warn("LLM request attempt {}/{} failed ({}); retrying in {} ms", attempt, maxAttempts,
                        e.getMessage(), delay);
                sleep(delay);
                backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
            }
        }
        throw new LlmException("LLM request failed");
    }

    private LlmResult streamOnce(Endpoint endpoint, LlmRequest request, Consumer<String> onTextDelta) {
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(endpoint.completionsUri())
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildBody(request)), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new LlmException("Could not create LLM request", e);
        }

        AtomicBoolean published = new AtomicBoolean(false);
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 429 || status >= 500) {
                closeQuietly(response.body());
                throw new RetryableLlmException("HTTP " + status, retryAfterMs(response));
            }
            if (status < 200 || status >= 300) {
                String detail = readAtMost(response.body(), 1_000);
                throw new LlmException("LLM call failed: HTTP " + status + (detail.isBlank() ? "" : " - " + detail));
            }
            return readSse(response.body(), token -> {
                published.set(true);
                onTextDelta.accept(token);
            });
        } catch (IOException e) {
            // A reconnect after visible output would duplicate text and can produce a different tool call.
            // The client may retry only a request that failed before its first published delta.
            if (published.get()) throw new LlmException("LLM stream interrupted after partial output", e);
            throw new RetryableLlmException("network error: " + e.getMessage(), 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted", e);
        }
    }

    private LlmResult readSse(InputStream stream, Consumer<String> onTextDelta) throws IOException {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();
        UsageBuilder usage = new UsageBuilder();
        String[] finishReason = { null };
        StringBuilder eventData = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (processEvent(eventData, content, reasoning, toolCalls, usage, finishReason, onTextDelta)) break;
                    eventData.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!eventData.isEmpty()) eventData.append('\n');
                    eventData.append(line.substring(5).trim());
                }
            }
            if (!eventData.isEmpty()) processEvent(eventData, content, reasoning, toolCalls, usage, finishReason, onTextDelta);
        }

        List<ToolCall> calls = toolCalls.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> entry.getValue().build(entry.getKey()))
                .toList();
        log.debug("llm_stream_done reasoningChars={} contentChars={}", reasoning.length(), content.length());
        return new LlmResult(content.toString(), reasoning.isEmpty() ? null : reasoning.toString(), calls,
                new Usage(usage.promptTokens, usage.completionTokens), finishReason[0]);
    }

    /** @return true when the terminal [DONE] event was received. */
    private boolean processEvent(StringBuilder raw, StringBuilder content, StringBuilder reasoning,
                                 Map<Integer, ToolCallBuilder> toolCalls, UsageBuilder usage, String[] finishReason,
                                 Consumer<String> onTextDelta) {
        String data = raw.toString();
        if (data.isBlank()) return false;
        if ("[DONE]".equals(data)) return true;
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode usageNode = root.path("usage");
            usage.promptTokens = usageNode.path("prompt_tokens").asInt(usage.promptTokens);
            usage.completionTokens = usageNode.path("completion_tokens").asInt(usage.completionTokens);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return false;
            JsonNode choice = choices.get(0);
            if (!choice.path("finish_reason").isNull()) finishReason[0] = choice.path("finish_reason").asText(null);
            JsonNode delta = choice.path("delta");
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                content.append(text);
                onTextDelta.accept(text);
            }
            // Reasoning-capable models (e.g. DeepSeek R1-style, OpenRouter "reasoning" field) stream their
            // chain-of-thought separately from the final answer; different providers name it differently.
            String reasoningText = delta.path("reasoning").asText(delta.path("reasoning_content").asText(""));
            if (!reasoningText.isEmpty()) reasoning.append(reasoningText);
            JsonNode toolNodes = delta.path("tool_calls");
            if (toolNodes.isArray()) {
                for (JsonNode tool : toolNodes) {
                    int index = tool.path("index").asInt(0);
                    ToolCallBuilder target = toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                    if (!tool.path("id").isMissingNode()) target.id = tool.path("id").asText(target.id);
                    JsonNode function = tool.path("function");
                    if (!function.path("name").isMissingNode()) target.name = function.path("name").asText(target.name);
                    if (!function.path("arguments").isMissingNode()) target.arguments.append(function.path("arguments").asText(""));
                }
            }
        } catch (RuntimeException e) {
            log.debug("Ignoring malformed LLM SSE event: {}", e.getMessage());
        }
        return false;
    }

    private Map<String, Object> buildBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature() > 0 ? request.temperature() : defaultTemperature);
        body.put("stream", true);
        body.put("messages", request.messages().stream().map(this::messageToMap).toList());
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toolToMap).toList());
            body.put("tool_choice", "auto");
        }
        return body;
    }

    private Map<String, Object> messageToMap(LlmMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.role());
        if (m.content() != null) map.put("content", m.content());
        if (m.toolCallId() != null) map.put("tool_call_id", m.toolCallId());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall c : m.toolCalls()) {
                calls.add(Map.of("id", c.id(), "type", "function",
                        "function", Map.of("name", c.name(), "arguments", c.arguments())));
            }
            map.put("tool_calls", calls);
        }
        return map;
    }

    private Map<String, Object> toolToMap(ToolSpec t) {
        return Map.of("type", "function", "function", Map.of(
                "name", t.name(), "description", t.description(), "parameters", t.parameters()));
    }

    private static long retryAfterMs(HttpResponse<?> response) {
        String header = response.headers().firstValue("Retry-After").orElse("");
        try {
            return Math.max(0, Long.parseLong(header) * 1_000);
        } catch (NumberFormatException ignored) {
            try {
                return Math.max(0, Duration.between(Instant.now(), Instant.parse(header)).toMillis());
            } catch (RuntimeException ignoredAgain) {
                return 0;
            }
        }
    }

    private static String readAtMost(InputStream stream, int maximum) throws IOException {
        try (stream) { return new String(stream.readNBytes(maximum), StandardCharsets.UTF_8); }
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    /** Equal jitter: half the backoff fixed, half random â€” spreads concurrent retries so a provider-wide
     *  429/5xx doesn't make every worker retry in lockstep (thundering herd). */
    private static long withJitter(long baseMs) {
        if (baseMs <= 0) return 0;
        long half = baseMs / 2;
        return half + ThreadLocalRandom.current().nextLong(half + 1);
    }

    private static void sleep(long delayMs) {
        try { Thread.sleep(delayMs); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry interrupted", e);
        }
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
        private ToolCall build(int index) {
            return new ToolCall(id == null ? "tool-" + index : id, name == null ? "unknown" : name, arguments.toString());
        }
    }

    private static final class UsageBuilder { private int promptTokens; private int completionTokens; }

    private record Endpoint(URI completionsUri, String apiKey) {
        private Endpoint(String baseUrl, String apiKey) {
            this(completionsUri(baseUrl), apiKey == null ? "" : apiKey);
        }

        private static URI completionsUri(String baseUrl) {
            String normalized = baseUrl.replaceAll("/+$", "");
            String path = normalized.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
            return URI.create(normalized + path);
        }
    }
}
