package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import jakarta.annotation.PostConstruct;
import java.io.FilterInputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Set<String> streamUsageProviders = new HashSet<>();
    private final double defaultTemperature;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Duration requestTimeout;
    private final Duration rejectedKeyCooldown;
    private final long streamIdleTimeoutNanos;
    private boolean accountingRequired;

    @PostConstruct
    public void requireAccountingForManagedUse() { accountingRequired = true; }

    public OpenAiCompatibleLlmClient(AgentProperties props, ObjectMapper objectMapper) {
        AgentProperties.Llm llm = props.getLlm();
        this.objectMapper = objectMapper;
        this.defaultTemperature = llm.getTemperature();
        this.maxAttempts = Math.max(1, llm.getMaxAttempts());
        this.initialBackoffMs = Math.max(50, llm.getRetryInitialBackoffMs());
        this.maxBackoffMs = Math.max(initialBackoffMs, llm.getRetryMaxBackoffMs());
        this.requestTimeout = Duration.ofMillis(Math.max(1_000, llm.getRequestTimeoutMs()));
        this.rejectedKeyCooldown = Duration.ofMillis(Math.max(1_000, llm.getRejectedKeyCooldownMs()));
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
                    String normalized = normalizeProvider(name);
                    configured.put(normalized, new EndpointPool(eps));
                    if (provider.isIncludeStreamUsage()) {
                        streamUsageProviders.add(normalized);
                    }
                    if (provider.getReasoningEffort() != null && !provider.getReasoningEffort().isBlank()) {
                        reasoningEfforts.put(normalized, provider.getReasoningEffort().trim());
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
        String provider = normalizeProvider(request.provider());
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
        // The run's Stop signal (bound to the calling thread by the agent): it aborts the wait for the
        // response, the stream and the back-off between attempts.
        RunCancellation cancellation = RunCancellation.current();
        int maxRateLimited = maxAttempts * pool.size();
        int rateLimited = 0;
        int transientFailures = 0;
        int keysTriedInRound = 0;
        long backoffMs = initialBackoffMs;
        while (true) {
            if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()) throw new LlmCancelledException();
            Duration attemptTimeout = attemptTimeout(deadline, provider);
            Endpoint endpoint = pool.next();
            if (endpoint == null) {
                throw new LlmHttpException(401, "LLM call failed: HTTP 401 - every API key of '" + provider + "' was rejected recently");
            }
            try {
                return streamOnce(endpoint, request, onTextDelta, attemptTimeout, deadline, cancellation);
            } catch (KeyRejectedException rejected) {
                // A revoked or mistyped key: set it aside and go on at once with the provider's other keys.
                pool.disable(endpoint, rejectedKeyCooldown);
                log.warn("API key {} of {} for '{}' was rejected (HTTP 401); skipping it for {} min",
                        pool.indexOf(endpoint) + 1, pool.size(), provider, rejectedKeyCooldown.toMinutes());
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
                sleep(delay, cancellation);
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
                                 Duration attemptTimeout, long deadline, RunCancellation cancellation) {
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

        LlmRequest attemptRequest=request.budget()==null?request:new LlmRequest(request.provider(),request.model(),request.messages(),request.tools(),request.temperature(),
                Duration.ofNanos(Math.max(1,remainingNanos(deadline))),request.toolChoice(),request.maxTokens(),request.jsonSchema());
        return LlmAttemptScope.execute(attemptRequest,
                () -> executePreparedRequest(httpRequest, onTextDelta, deadline, cancellation), accountingRequired);
    }

    private LlmResult executePreparedRequest(HttpRequest httpRequest, Consumer<String> onTextDelta,
                                            long deadline, RunCancellation cancellation) {
        AtomicBoolean published = new AtomicBoolean(false);
        StreamWatchdog watchdog = null;
        try {
            HttpResponse<InputStream> response = awaitResponse(httpRequest, cancellation,deadline);
            int status = response.statusCode();
            if (status == 429) {
                closeQuietly(response.body());
                LlmAttemptScope.responseClosed();
                throw RetryableLlmException.rateLimited(retryAfterMs(response));
            }
            if (status >= 500) {
                closeQuietly(response.body());
                LlmAttemptScope.responseClosed();
                throw new RetryableLlmException("HTTP " + status, retryAfterMs(response));
            }
            if (status == 401) {
                closeQuietly(response.body());
                LlmAttemptScope.responseClosed();
                throw new KeyRejectedException();
            }
            if (status < 200 || status >= 300) {
                // The status proves rejection. Do not wait for an untrusted error body, expose it,
                // or turn a known non-billed 4xx into an UNKNOWN timeout/fallback attempt.
                closeQuietly(response.body());
                LlmAttemptScope.responseClosed();
                throw new LlmHttpException(status, "LLM call failed: HTTP " + status);
            }
            // The request timeout stops at the response headers; the body needs its own guard.
            watchdog = new StreamWatchdog(response.body(), streamIdleTimeoutNanos, deadline, cancellation);
            return readSse(watchdog.stream(), token -> {
                published.set(true);
                onTextDelta.accept(token);
            });
        } catch (IOException e) {
            if (cancellation.isCancelled()) throw new LlmCancelledException();
            String reason = watchdog != null && watchdog.fired() != null
                    ? watchdog.fired() : "network error: " + e.getMessage();
            // A reconnect after visible output would duplicate text and can produce a different tool call.
            // The client may retry only a request that failed before its first published delta.
            if (published.get()) throw new LlmException("LLM stream interrupted after partial output (" + reason + ")", e);
            throw new RetryableLlmException(reason, 0, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if(cancellation.isCancelled()) throw new LlmCancelledException();
            throw new LlmException("LLM request interrupted", e);
        } finally {
            if (watchdog != null) watchdog.stop();
        }
    }

    /** Polls step while waiting for the response headers, so a Stop does not wait for the request timeout. */
    private static final long CANCEL_POLL_MS = 100;

    /**
     * Sends the request and waits for the response headers, aborting the request when the run is
     * cancelled. Transport failures surface as IOException, as with a blocking send.
     */
    private HttpResponse<InputStream> awaitResponse(HttpRequest httpRequest, RunCancellation cancellation,long deadline)
            throws IOException, InterruptedException {
        if(remainingNanos(deadline)<=0)throw new IOException("Call time budget exhausted before headers");
        CompletableFuture<HttpResponse<InputStream>> pending = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        while (true) {
            try {
                return pending.get(CANCEL_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException waiting) {
                if(remainingNanos(deadline)<=0){pending.cancel(true);throw new IOException("Call time budget exhausted while waiting for headers");}
                if (cancellation.isCancelled()) {
                    pending.cancel(true);
                    throw new LlmCancelledException();
                }
            } catch (InterruptedException interrupted) {
                pending.cancel(true);
                throw interrupted;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof IOException io) throw io;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw new IOException(cause);
            }
        }
    }

    /**
     * Closes a response body that has gone silent for {@code idleNanos}, or that outlives the call's
     * deadline, or whose run was cancelled. Closing unblocks the reader with an IOException, which the caller turns into a
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

        private final RunCancellation cancellation;

        StreamWatchdog(InputStream source, long idleNanos, long deadline, RunCancellation cancellation) {
            this.cancellation = cancellation;
            this.source = source;
            this.tracked = new FilterInputStream(source) {
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
                if (cancellation.isCancelled()) { fire("cancelled"); return; }
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
                usage.reported ? new Usage(usage.promptTokens, usage.completionTokens) : null, finishReason[0]);
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
            JsonNode prompt = usageNode.path("prompt_tokens"), completion = usageNode.path("completion_tokens");
            if (prompt.isIntegralNumber() && completion.isIntegralNumber()
                    && prompt.asLong() >= 0 && prompt.asLong() <= Integer.MAX_VALUE
                    && completion.asLong() >= 0 && completion.asLong() <= Integer.MAX_VALUE) {
                usage.promptTokens = prompt.asInt();
                usage.completionTokens = completion.asInt();
                usage.reported = true;
            }
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

    Map<String, Object> buildBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature());
        body.put("stream", true);
        String provider = normalizeProvider(request.provider());
        if (streamUsageProviders.contains(provider)) {
            body.put("stream_options", Map.of("include_usage", true));
        }
        body.put("messages", request.messages().stream().map(this::messageToMap).toList());
        if(request.jsonSchema()!=null) {
            var schema=objectMapper.readTree(request.jsonSchema().schema());
            if(!schema.isObject())throw new IllegalArgumentException("Response schema must be an object");
            body.put("response_format",Map.of("type","json_schema","json_schema",Map.of("name",request.jsonSchema().name(),"schema",schema,"strict",true)));
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::toolToMap).toList());
            body.put("tool_choice", request.toolChoice() == null ? "auto" : request.toolChoice());
        }
        if (request.maxTokens() != null && request.maxTokens() > 0) body.put("max_tokens", request.maxTokens());
        String effort = reasoningEfforts.get(provider);
        if (effort != null) body.put("reasoning_effort", effort);
        return body;
    }

    private static String normalizeProvider(String provider) {
        return provider == null || provider.isBlank() ? "openrouter" : provider.strip().toLowerCase(Locale.ROOT);
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

    /** Back-off between attempts that ends early when the run is cancelled. */
    private static void sleep(long delayMs, RunCancellation cancellation) {
        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        try {
            while (System.nanoTime() < until) {
                if (cancellation.isCancelled()) throw new LlmCancelledException();
                Thread.sleep(Math.min(CANCEL_POLL_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime()))));
            }
        } catch (InterruptedException e) {
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

    private static final class UsageBuilder { private int promptTokens; private int completionTokens; private boolean reported; }

    /** HTTP 401 for one key; the call goes on with the provider's other keys. */
    private static final class KeyRejectedException extends LlmHttpException {
        KeyRejectedException() { super(401,"HTTP 401"); }
    }

    private static final class EndpointPool {
        private final List<Endpoint> endpoints;
        private final AtomicInteger index = new AtomicInteger();
        /** Keys the provider rejected, until when (nanoTime). */
        private final Map<Endpoint, Long> disabledUntil = new ConcurrentHashMap<>();

        EndpointPool(List<Endpoint> endpoints) { this.endpoints = List.copyOf(endpoints); }

        /** The next key in rotation that is not set aside, or null when every key is. */
        Endpoint next() {
            for (int i = 0; i < endpoints.size(); i++) {
                Endpoint candidate = endpoints.get(Math.floorMod(index.getAndIncrement(), endpoints.size()));
                Long until = disabledUntil.get(candidate);
                if (until == null) return candidate;
                if (System.nanoTime() - until >= 0) { disabledUntil.remove(candidate, until); return candidate; }
            }
            return null;
        }

        void disable(Endpoint endpoint, Duration duration) { disabledUntil.put(endpoint, System.nanoTime() + duration.toNanos()); }
        int indexOf(Endpoint endpoint) { return endpoints.indexOf(endpoint); }
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
