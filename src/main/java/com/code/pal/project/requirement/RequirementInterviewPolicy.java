package com.code.pal.project.requirement;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Product policy is a resource; the admission boundary never invents extra discovery questions. */
public final class RequirementInterviewPolicy {
    private static final Set<String> REDUNDANT_KEYS = new HashSet<>();
    private static final Pattern REDUNDANT_PROMPT;

    static {
        try (var input = RequirementInterviewPolicy.class.getResourceAsStream("/policies/requirement-interview.json")) {
            if (input == null) throw new IllegalStateException("Requirement interview policy is missing");
            var policy = new ObjectMapper().readTree(input);
            policy.path("redundantKeys").forEach(key -> REDUNDANT_KEYS.add(key.asString()));
            REDUNDANT_PROMPT = Pattern.compile(policy.path("redundantPrompt").asString());
        } catch (IOException invalid) {
            throw new IllegalStateException("Requirement interview policy cannot be read", invalid);
        }
    }

    private RequirementInterviewPolicy() {}

    public static boolean redundant(String key, String prompt) {
        return REDUNDANT_KEYS.contains(key.toLowerCase(Locale.ROOT)) || REDUNDANT_PROMPT.matcher(prompt).find();
    }
}
