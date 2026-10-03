package com.code.pal.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

public final class PromptLoader {

    public enum Prompt {
        SYSTEM_BASE("system-base"),
        MODE_BUILD("mode-build"),
        MODE_CHAT("mode-chat"),
        MODE_PLAN("mode-plan"),
        CONVERSATIONAL("conversational"),
        INTENT_CLASSIFIER("intent-classifier");

        private final String filename;
        Prompt(String filename) { this.filename = filename; }
        String filename() { return filename; }
    }

    private static final Map<Prompt, String> CACHE = new EnumMap<>(Prompt.class);

    private PromptLoader() {}

    public static String load(Prompt prompt) {
        return CACHE.computeIfAbsent(prompt, PromptLoader::readFromClasspath);
    }

    private static String readFromClasspath(Prompt prompt) {
        String path = "prompts/" + prompt.filename() + ".md";
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (is == null) throw new IllegalStateException("Prompt not found on classpath: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt: " + path, e);
        }
    }
}
