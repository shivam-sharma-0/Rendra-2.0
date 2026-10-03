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
     * @param budget wall-clock limit for the whole call, retries and streaming included; when it runs
     *               out the call fails so the caller can fall back. {@code null} means only the
     *               per-attempt request timeout applies.
     */
    record LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools,
                      double temperature, Duration budget) {
        public LlmRequest(String provider, String model, List<LlmMessage> messages, List<ToolSpec> tools, double temperature) {
            this(provider, model, messages, tools, temperature, null);
        }

        /** Backwards-compatible constructor: unqualified requests use the OpenRouter fallback. */
        public LlmRequest(String model, List<LlmMessage> messages, List<ToolSpec> tools, double temperature) {
            this("openrouter", model, messages, tools, temperature, null);
        }
    }

    record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public static LlmMessage system(String content) { return new LlmMessage("system", content, null, null); }
        public static LlmMessage user(String content) { return new LlmMessage("user", content, null, null); }
        public static LlmMessage assistant(String content) { return new LlmMessage("assistant", content, null, null); }
        public static LlmMessage assistantToolCalls(List<ToolCall> calls) { return new LlmMessage("assistant", null, calls, null); }
        public static LlmMessage toolResult(String toolCallId, String content) { return new LlmMessage("tool", content, null, toolCallId); }
    }

    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    record ToolCall(String id, String name, String arguments) {}

    record LlmResult(String content, String reasoning, List<ToolCall> toolCalls, Usage usage, String finishReason) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    record Usage(int promptTokens, int completionTokens) {}
}
