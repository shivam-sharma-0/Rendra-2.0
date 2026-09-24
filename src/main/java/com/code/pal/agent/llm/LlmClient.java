package com.code.pal.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic LLM port. One implementation ({@link OpenAiCompatibleLlmClient}) covers Groq,
 * OpenRouter, and OpenAI since they share the wire format; a genuinely different provider
 * (e.g. Anthropic) would be another implementation of this same interface.
 *
 * <p>The DTOs are nested to keep the contract in one place. They are intentionally minimal —
 * just what the agent loop needs: text, tool calls, and token usage.
 */
public interface LlmClient {

    /** One synchronous chat completion. Streaming is layered on in a later phase. */
    LlmResult chat(LlmRequest request);

    record LlmRequest(String model, List<LlmMessage> messages, List<ToolSpec> tools, double temperature) {}

    /**
     * A conversation turn. {@code role} is one of system/user/assistant/tool.
     * {@code toolCalls} is set on an assistant turn that invoked tools; {@code toolCallId} links a
     * tool-result turn back to the call it answers.
     */
    record LlmMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public static LlmMessage system(String content) { return new LlmMessage("system", content, null, null); }
        public static LlmMessage user(String content) { return new LlmMessage("user", content, null, null); }
        public static LlmMessage assistant(String content) { return new LlmMessage("assistant", content, null, null); }
        public static LlmMessage assistantToolCalls(List<ToolCall> calls) { return new LlmMessage("assistant", null, calls, null); }
        public static LlmMessage toolResult(String toolCallId, String content) { return new LlmMessage("tool", content, null, toolCallId); }
    }

    /** A tool exposed to the model: name + description + JSON-schema of its parameters. */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /** A tool invocation the model asked for. {@code arguments} is a raw JSON string. */
    record ToolCall(String id, String name, String arguments) {}

    /** Result of a completion: either free text, or tool calls to execute, plus usage. */
    record LlmResult(String content, List<ToolCall> toolCalls, Usage usage, String finishReason) {
        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }
    }

    record Usage(int promptTokens, int completionTokens) {}
}
