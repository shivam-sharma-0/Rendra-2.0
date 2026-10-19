package com.code.pal.agent.intent;

import com.code.pal.agent.contract.ModelJson;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

/** One read-only decision and reply; neither JSON nor the action grants mutation authority. */
public record UnclearReply(String reply, String action) {
    public static final String POLICY = PromptLoader.load(Prompt.UNCLEAR_REPLY);

    public static UnclearReply decode(ObjectMapper mapper, String content, String request) {
        try {
            var json = mapper.readTree(ModelJson.extractObject(content));
            String reply = json.path("reply").asString("").strip();
            String action = json.path("action").asString("");
            if (json.isObject() && json.path("reply").isString() && json.path("action").isString()
                    && !reply.isBlank() && Set.of("answer", "build", "ask").contains(action)) {
                return new UnclearReply(reply, action);
            }
        } catch (RuntimeException invalid) {
            // Invalid/truncated JSON gets one safe clarification, never another model call or an offer.
        }
        boolean hinglish = RequestRouter.normalize(request).matches(".*\\b(kya|hai|karo|chahiye|mujhe|haan)\\b.*");
        return new UnclearReply(hinglish ? "Aap kya karna chahte hain?" : "What would you like to do?", "ask");
    }
}
