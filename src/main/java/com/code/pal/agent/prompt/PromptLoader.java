package com.code.pal.agent.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PromptLoader {

    public enum Prompt {
        SYSTEM_BASE("system-base"),
        MODE_BUILD("mode-build"),
        MODE_CHAT("mode-chat"),
        MODE_PLAN("mode-plan"),
        CONVERSATIONAL("conversational"),
        STACK_STATIC("stack-static"),
        STACK_VITE("stack-vite"),
        STACK_SUPABASE("stack-supabase"),
        STACK_MERN("stack-mern"),
        STACK_UNDECIDED("stack-undecided");

        private final String filename;
        Prompt(String filename) { this.filename = filename; }
        String filename() { return filename; }
    }

    private static final Map<Prompt, String> CACHE = new ConcurrentHashMap<>();

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
