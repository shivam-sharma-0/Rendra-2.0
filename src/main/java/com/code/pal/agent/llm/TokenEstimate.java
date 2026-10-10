package com.code.pal.agent.llm;

import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.ToolCall;

import java.util.List;

/** A rough upper estimate of a request's input tokens, for reserving quota before the call. */
public final class TokenEstimate {

    /** What one attached image costs a vision model, roughly; its data URL is not counted as text. */
    static final int TOKENS_PER_IMAGE = 1_500;

    private TokenEstimate() {
    }

    /** About four characters per token on everything sent (messages, tool calls, tool specs), plus images. */
    public static long input(List<LlmMessage> messages, int toolSpecChars) {
        long chars = toolSpecChars;
        long images = 0;
        for (LlmMessage message : messages) {
            chars += 16;   // role and framing
            if (message.content() != null) chars += message.content().length();
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    chars += (call.name() == null ? 0 : call.name().length()) + (call.arguments() == null ? 0 : call.arguments().length());
                }
            }
            if (message.images() != null) images += message.images().size();
        }
        return chars / 4 + 1 + images * TOKENS_PER_IMAGE;
    }

    public static long input(LlmClient.LlmRequest request) {
        return input(request.messages(), request.tools() == null || request.tools().isEmpty() ? 0 : request.tools().toString().length());
    }
}
