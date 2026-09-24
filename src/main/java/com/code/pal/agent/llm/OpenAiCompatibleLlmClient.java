package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM client for any OpenAI-compatible {@code /v1/chat/completions} endpoint (Groq today).
 * Maps our provider-agnostic {@link LlmClient} DTOs to/from the OpenAI wire format.
 *
 * <p>A single {@link RestClient} is built once (connection reuse / keep-alive). Streaming and
 * transient-error retry (429/5xx) are added in the next phase; this base does one blocking call.
 */
@Slf4j
@Component
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient http;
    private final double defaultTemperature;

    public OpenAiCompatibleLlmClient(AgentProperties props) {
        AgentProperties.Llm llm = props.getLlm();
        this.defaultTemperature = llm.getTemperature();
        this.http = RestClient.builder()
                .baseUrl(llm.getBaseUrl() == null ? "" : llm.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + (llm.getApiKey() == null ? "" : llm.getApiKey()))
                .build();
    }

    @Override
    public LlmResult chat(LlmRequest request) {
        Map<String, Object> body = buildBody(request);

        WireResponse res = http.post()
                .uri("/v1/chat/completions")
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, resp) -> {
                    throw new LlmException("LLM call failed: HTTP " + resp.getStatusCode());
                })
                .body(WireResponse.class);

        return toResult(res);
    }

    /* ── Request mapping (our DTOs → OpenAI JSON) ─────────────────────────────── */

    private Map<String, Object> buildBody(LlmRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("temperature", request.temperature() > 0 ? request.temperature() : defaultTemperature);
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
                calls.add(Map.of(
                        "id", c.id(),
                        "type", "function",
                        "function", Map.of("name", c.name(), "arguments", c.arguments())));
            }
            map.put("tool_calls", calls);
        }
        return map;
    }

    private Map<String, Object> toolToMap(ToolSpec t) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", t.name(),
                        "description", t.description(),
                        "parameters", t.parameters()));
    }

    /* ── Response mapping (OpenAI JSON → our DTOs) ────────────────────────────── */

    private LlmResult toResult(WireResponse res) {
        if (res == null || res.choices() == null || res.choices().isEmpty()) {
            return new LlmResult("", List.of(), new Usage(0, 0), "empty");
        }
        Choice choice = res.choices().get(0);
        WireMessage msg = choice.message();

        List<ToolCall> toolCalls = new ArrayList<>();
        if (msg != null && msg.tool_calls() != null) {
            for (WireToolCall tc : msg.tool_calls()) {
                toolCalls.add(new ToolCall(tc.id(), tc.function().name(), tc.function().arguments()));
            }
        }
        Usage usage = res.usage() == null
                ? new Usage(0, 0)
                : new Usage(res.usage().prompt_tokens(), res.usage().completion_tokens());

        return new LlmResult(msg == null ? null : msg.content(), toolCalls, usage, choice.finish_reason());
    }

    /* ── Wire records (OpenAI shape; field names match JSON) ───────────────────── */

    private record WireResponse(List<Choice> choices, WireUsage usage) {}
    private record Choice(WireMessage message, String finish_reason) {}
    private record WireMessage(String role, String content, List<WireToolCall> tool_calls) {}
    private record WireToolCall(String id, WireFunction function) {}
    private record WireFunction(String name, String arguments) {}
    private record WireUsage(int prompt_tokens, int completion_tokens) {}
}
