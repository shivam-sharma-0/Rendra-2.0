package com.code.pal.agent.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Provider-neutral model gateway port used by the agent runtime. */
public interface LlmClient {

    /** Execute a completion and deliver each text delta as soon as the provider sends it. */
    LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta);

    /** Convenience form for callers which only need the completed result. */
    default LlmResult chat(LlmRequest request) {
        return chatStreaming(request, ignored -> { });
    }

    /**
     * @param budget     wall-clock limit for the whole call, retries and streaming included; when it runs
     *                   out the call fails so the caller can fall back. {@code null} means only the
     *                   per-attempt request timeout applies.
     * @param toolChoice OpenAI tool_choice: null for "auto"; "required" when the turn must call a tool
     * @param maxTokens  cap on the generated tokens ({@code max_tokens}); null leaves the provider's default.
     *                   It also bounds what a call can cost, which the daily quota reserves in advance.
     */
    record LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools,
                      double temperature, Duration budget, String toolChoice, Integer maxTokens, JsonSchema jsonSchema) {
        public LlmRequest(String provider,String model,List<LlmMessage> messages,List<ToolSpec> tools,double temperature,Duration budget,String toolChoice,Integer maxTokens) {
            this(provider,model,messages,tools,temperature,budget,toolChoice,maxTokens,null);
        }
        public LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools,
                          double temperature, Duration budget, String toolChoice) {
            this(provider, model, messages, tools, temperature, budget, toolChoice, null);
        }

        public LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools,
                          double temperature, Duration budget) {
            this(provider, model, messages, tools, temperature, budget, null);
        }

        public LlmRequest withMaxTokens(Integer cap) {
            return new LlmRequest(provider, model, messages, tools, temperature, budget, toolChoice, cap,jsonSchema);
        }
        public LlmRequest withJsonSchema(JsonSchema schema){return new LlmRequest(provider,model,messages,tools,temperature,budget,toolChoice,maxTokens,schema);}

        public LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools, double temperature) {
            this(provider, model, messages, tools, temperature, null);
        }

        /** Backwards-compatible constructor: unqualified requests use the OpenRouter fallback. */
        public LlmRequest(String model, List<LlmMessage> messages, List<ToolSpec> tools, double temperature) {
            this("openrouter", model, messages, tools, temperature, null);
        }
    }
    /** Backend-authored response grammar. It is data, not permission to mutate or raise limits. */
    record JsonSchema(String name,String schema) {
        public JsonSchema {if(name==null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || schema==null || schema.length()>32_000)throw new IllegalArgumentException("Invalid response schema");}
    }

    /** {@code images}: data URLs ({@code data:image/png;base64,...}) sent with a user message to a vision model. */
    record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId, List<String> images) {
        public LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
            this(role, content, toolCalls, toolCallId, null);
        }
        public static LlmMessage userWithImages(String content, List<String> images) {
            return new LlmMessage("user", content, null, null, List.copyOf(images));
        }
        public static LlmMessage system(String content) { return new LlmMessage("system", content, null, null); }
        public static LlmMessage user(String content) { return new LlmMessage("user", content, null, null); }
        public static LlmMessage assistant(String content) { return new LlmMessage("assistant", content, null, null); }
        public static LlmMessage assistantToolCalls(List<ToolCall> calls) { return new LlmMessage("assistant", null, calls, null); }
        public static LlmMessage toolResult(String toolCallId, String content) { return new LlmMessage("tool", content, null, toolCallId); }
    }

    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /**
     * {@code extraContent}: provider data that must travel back with the call on the next request, e.g.
     * Gemini's thought_signature ({@code extra_content.google.thought_signature}); null for most providers.
     */
    record ToolCall(String id, String name, String arguments, java.util.Map<String, Object> extraContent) {
        public ToolCall(String id, String name, String arguments) {
            this(id, name, arguments, null);
        }
    }

    record LlmResult(String content, String reasoning, List<ToolCall> toolCalls, Usage usage, String finishReason) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    record Usage(int promptTokens, int completionTokens) {}
}
