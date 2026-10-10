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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<String, EndpointPool> endpointPools;
    /** Per provider: the reasoning_effort to send, when configured. */
    private final Map<String, String> reasoningEfforts = new HashMap<>();
    private final double defaultTemperature;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Duration requestTimeout;
    private final long streamIdleTimeoutNanos;

    public OpenAiCompatibleLlmClient(AgentProperties props, ObjectMapper objectMapper) {
        AgentProperties.Llm llm = props.getLlm();
        this.objectMapper = objectMapper;
        this.defaultTemperature = llm.getTemperature();
        this.maxAttempts = Math.max(1, llm.getMaxAttempts());
        this.initialBackoffMs = Math.max(50, llm.getRetryInitialBackoffMs());
        this.maxBackoffMs = Math.max(initialBackoffMs, llm.getRetryMaxBackoffMs());
        this.requestTimeout = Duration.ofMillis(Math.max(1_000, llm.getRequestTimeoutMs()));
        this.streamIdleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(100, llm.getStreamIdleTimeoutMs()));
        Map<String, EndpointPool> configured = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((name, provider) -> {
                if (provider != null && provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
                    List<String> keys = provider.resolvedKeys();
                    if (keys.isEmpty()) keys = List.of("");
                    List<Endpoint> eps = keys.stream()
                            .map(k -> new Endpoint(provider.getBaseUrl(), k))
                            .toList();
                    configured.put(name.toLowerCase(java.util.Locale.ROOT), new EndpointPool(eps));
                    if (provider.getReasoningEffort() != null && !provider.getReasoningEffort().isBlank()) {
                        reasoningEfforts.put(name.toLowerCase(java.util.Locale.ROOT), provider.getReasoningEffort().trim());
                    }
                }
            });
        }
        if (!configured.containsKey("openrouter") && llm.getBaseUrl() != null && !llm.getBaseUrl().isBlank()) {
            configured.put("openrouter", new EndpointPool(List.of(new Endpoint(llm.getBaseUrl(), llm.getApiKey()))));
        }
        this.endpointPools = Map.copyOf(configured);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta) {
        String provider = request.provider() == null || request.provider().isBlank()
                ? "openrouter" : request.provider().toLowerCase(java.util.Locale.ROOT);
        EndpointPool pool = endpointPools.get(provider);
        if (pool == null || !pool.isConfigured()) {
            throw new LlmException("LLM provider is not configured: " + provider);
        }

        // Retry policy:
        //  - 429 is per key: rotate to the next key at once; back off only after every key of the
        //    provider is limited, for at most maxAttempts rounds.
        //  - Network errors, stalls and 5xx are per provider: another key cannot help, so they are
        //    retried at most maxAttempts times in total, with backoff.
        //  - Everything stays inside the request's budget; when it runs out the call fails so the
        //    caller can fall back to another model instead of waiting on this one.
        long deadline = request.budget() == null ? Long.MAX_VALUE
                : System.nanoTime() + request.budget().toNanos();
        int maxRateLimited = maxAttempts * pool.size();
        int rateLimited = 0;
        int transientFailures = 0;
        int keysTriedInRound = 0;
        long backoffMs = initialBackoffMs;
        while (true) {
            Duration attemptTimeout = attemptTimeout(deadline, provider);
            Endpoint endpoint = pool.next();
            try {
                return streamOnce(endpoint, request, onTextDelta, attemptTimeout, deadline);
            } catch (RetryableLlmException e) {
                if (e.rateLimited()) {
                    if (++rateLimited >= maxRateLimited) throw e;
                    if (++keysTriedInRound < pool.size()) {
                        log.info("Key {} of {} for '{}' rate-limited; rotating instantly",
                                keysTriedInRound, pool.size(), provider);
                        continue;
                    }
                    keysTriedInRound = 0;
                } else if (++transientFailures >= maxAttempts) {
                    throw e;
                }
                long delay = withJitter(e.retryAfterMs() > 0 ? e.retryAfterMs() : backoffMs);
                if (remainingNanos(deadline) <= TimeUnit.MILLISECONDS.toNanos(delay) + MIN_ATTEMPT_NANOS) {
                    throw new LlmException("LLM time budget exhausted for '" + provider + "' after: " + e.getMessage(), e);
                }
                log.warn("LLM request to '{}' failed ({}); retrying in {} ms", provider, e.getMessage(), delay);
                sleep(delay);
                backoffMs = Math.min(maxBackoffMs, backoffMs * 2);
            }
        }
    }

    /** Below this a new attempt cannot realistically complete, so the budget counts as spent. */
    private static final long MIN_ATTEMPT_NANOS = TimeUnit.SECONDS.toNanos(1);

    private Duration attemptTimeout(long deadline, String provider) {
        long remaining = remainingNanos(deadline);
        if (remaining <= MIN_ATTEMPT_NANOS) {
            throw new LlmException("LLM time budget exhausted for '" + provider + "'");
        }
        return remaining >= requestTimeout.toNanos() ? requestTimeout : Duration.ofNanos(remaining);
    }

    private static long remainingNanos(long deadline) {
        return deadline == Long.MAX_VALUE ? Long.MAX_VALUE : deadline - System.nanoTime();
    }

    private LlmResult streamOnce(Endpoint endpoint, LlmRequest request, Consumer<String> onTextDelta,
                                 Duration attemptTimeout, long deadline) {
        HttpRequest httpRequest;
        try {
            httpRequest = HttpRequest.newBuilder(endpoint.completionsUri())
                    .timeout(attemptTimeout)
                    .header("Authorization", "Bearer " + endpoint.apiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(buildBody(request)), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            throw new LlmException("Could not create LLM request", e);
        }

        AtomicBoolean published = new AtomicBoolean(false);
        StreamWatchdog watchdog = null;
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status == 429) {
                closeQuietly(response.body());
                throw RetryableLlmException.rateLimited(retryAfterMs(response));
            }
            if (status >= 500) {
                closeQuietly(response.body());
                throw new RetryableLlmException("HTTP " + status, retryAfterMs(response));
            }
            if (status < 200 || status >= 300) {
                String detail = readAtMost(response.body(), 1_000);
                throw new LlmException("LLM call failed: HTTP " + status + (detail.isBlank() ? "" : " - " + detail));
            }
            // The request timeout stops at the response headers; the body needs its own guard.
            watchdog = new StreamWatchdog(response.body(), streamIdleTimeoutNanos, deadline);
            return readSse(watchdog.stream(), token -> {
                published.set(true);
                onTextDelta.accept(token);
            });
        } catch (IOException e) {
            String reason = watchdog != null && watchdog.fired() != null
                    ? watchdog.fired() : "network error: " + e.getMessage();
            // A reconnect after visible output would duplicate text and can produce a different tool call.
            // The client may retry only a request that failed before its first published delta.
            if (published.get()) throw new LlmException("LLM stream interrupted after partial output (" + reason + ")", e);
            throw new RetryableLlmException(reason, 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM request interrupted", e);
        } finally {
            if (watchdog != null) watchdog.stop();
        }
    }

    /**
     * Closes a response body that has gone silent for {@code idleNanos}, or that outlives the call's
     * deadline. Closing unblocks the reader with an IOException, which the caller turns into a
     * retry (no output yet) or a failure (output already published).
     */
    private static final class StreamWatchdog {
        private static final long CHECK_INTERVAL_MS = 200;

        private final InputStream source;
        private final AtomicLong lastActivity = new AtomicLong(System.nanoTime());
        private final InputStream tracked;
        private final Thread thread;
        private volatile boolean stopped;
        private volatile String fired;

        StreamWatchdog(InputStream source, long idleNanos, long deadline) {
            this.source = source;
            this.tracked = new java.io.FilterInputStream(source) {
                @Override public int read() throws IOException {
                    int value = super.read();
                    lastActivity.set(System.nanoTime());
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = super.read(buffer, offset, length);
                    lastActivity.set(System.nanoTime());
                    return count;
                }
            };
            this.thread = Thread.ofVirtual().name("llm-stream-watchdog").start(() -> watch(idleNanos, deadline));
        }

        private void watch(long idleNanos, long deadline) {
            while (!stopped) {
                long now = System.nanoTime();
                if (now - lastActivity.get() >= idleNanos) { fire("stream stalled"); return; }
                if (deadline != Long.MAX_VALUE && now >= deadline) { fire("time budget exhausted mid-stream"); return; }
                try { Thread.sleep(CHECK_INTERVAL_MS); } catch (InterruptedException e) { return; }
            }
        }

        private void fire(String reason) {
            if (stopped) return;
            fired = reason;
            closeQuietly(source);
        }

        InputStream stream() { return tracked; }
        String fired() { return fired; }

        void stop() {
            stopped = true;
            thread.interrupt();
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
                    if (tool.path("extra_content").isObject()) target.extraContent = objectMapper.convertValue(tool.path("extra_content"), Map.class);
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
            body.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
        }
        String effort = request.provider() == null ? null : reasoningEfforts.get(request.provider().toLowerCase(java.util.Locale.ROOT));
        if (effort != null) body.put("reasoning_effort", effort);
        return body;
    }

    private Map<String, Object> messageToMap(LlmMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", m.role());
        if (m.images() != null && !m.images().isEmpty()) {
            // OpenAI-compatible multimodal content: the text part, then one image part per image.
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("type", "text", "text", m.content() == null ? "" : m.content()));
            for (String image : m.images()) parts.add(Map.of("type", "image_url", "image_url", Map.of("url", image)));
            map.put("content", parts);
        } else if (m.content() != null) {
            map.put("content", m.content());
        }
        if (m.toolCallId() != null) map.put("tool_call_id", m.toolCallId());
        if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall c : m.toolCalls()) {
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("id", c.id());
                call.put("type", "function");
                call.put("function", Map.of("name", c.name(), "arguments", c.arguments()));
                if (c.extraContent() != null) call.put("extra_content", c.extraContent());
                calls.add(call);
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
        private Map<String, Object> extraContent;
        @SuppressWarnings("unchecked")
        private ToolCall build(int index) {
            return new ToolCall(id == null ? "tool-" + index : id, name == null ? "unknown" : name, arguments.toString(), extraContent);
        }
    }

    private static final class UsageBuilder { private int promptTokens; private int completionTokens; }

    private static final class EndpointPool {
        private final List<Endpoint> endpoints;
        private final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();

        EndpointPool(List<Endpoint> endpoints) { this.endpoints = List.copyOf(endpoints); }
        Endpoint next() { return endpoints.get(Math.floorMod(index.getAndIncrement(), endpoints.size())); }
        int size() { return endpoints.size(); }
        boolean isConfigured() { return !endpoints.isEmpty() && endpoints.stream().anyMatch(e -> !e.apiKey().isBlank()); }
    }

    private record Endpoint(URI completionsUri, String apiKey) {
        private Endpoint(String baseUrl, String apiKey) {
            this(completionsUri(baseUrl), apiKey == null ? "" : apiKey);
        }

        private static URI completionsUri(String baseUrl) {
            String normalized = baseUrl.replaceAll("/+$", "");
            if (normalized.endsWith("/chat/completions")) return URI.create(normalized);
            String path = (normalized.endsWith("/v1") || normalized.endsWith("/openai"))
                    ? "/chat/completions" : "/v1/chat/completions";
            return URI.create(normalized + path);
        }
    }
}
