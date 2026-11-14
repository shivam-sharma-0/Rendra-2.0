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
        UI_KIT("ui-kit"),
        MODE_CHAT("mode-chat"),
        MODE_PLAN("mode-plan"),
        CONVERSATIONAL("conversational"),
        UNCLEAR_REPLY("unclear-reply"),
        PRELOADED_FILES("preloaded-files"),
        CONTRACT_PLANNER("contract-planner"),
        CONTRACT_PLANNER_AUTHORITY("contract-planner-authority"),
        CONTRACT_PROPOSAL_TOOL("contract-proposal-tool"),
        CONTRACT_FORMAT_JSON("contract-format-json"),
        CONTRACT_FORMAT_TOOL("contract-format-tool"),
        CONTRACT_FRESH_MERN("contract-fresh-mern"),
        CONTRACT_PROPOSAL_CORRECTION("contract-proposal-correction"),
        CONTRACT_SCHEMA_CORRECTION("contract-schema-correction"),
        UNIT_GENERATOR("unit-generator"),
        BATCH_UNIT_GENERATOR("batch-unit-generator"),
        SPEC_CONTRACT_PLANNER("spec-contract-planner"),
        STRUCTURED_REPAIR("structured-repair"),
        UNIT_OUTPUT_CORRECTION("unit-output-correction"),
        BATCH_OUTPUT_CORRECTION("batch-output-correction"),
        SPEC_PLAN_CORRECTION("spec-plan-correction"),
        SPEC_PLAN_PROPOSAL("spec-plan-proposal"),
        CONTRACT_IMPORTS("contract-imports"),
        CONTRACT_NAVIGATION_VITE("contract-navigation-vite"),
        CONTRACT_NAVIGATION_STATIC("contract-navigation-static"),
        RESUME_PLAN("resume-plan"),
        REQUIREMENTS_SYSTEM("requirements-system"),
        RUNTIME_CAPABILITIES("runtime-capabilities"),
        REQUIREMENTS_ATTACHMENTS("requirements-attachments"),
        REQUIREMENTS("requirements"),
        TOOL_START_PROJECT("tools/start-project"),
        TRUNCATED_OUTPUT("truncated-output"),
        TOOL_RETIRE_ACCEPTANCE("tools/retire-acceptance"),
        TOOL_LOOP_ACCEPTANCE("tool-loop-acceptance"),
        FEATURE_OUTCOMES("feature-outcomes"),
        BUILD_DECISION("build-decision"),
        SPEC_ACCEPTANCE("spec-acceptance"),
        APPROVED_MEMORY("approved-memory"),
        VISUAL_REVIEW("visual-review"),
        VISUAL_REFINEMENT("visual-refinement"),
        ATTACHMENT_VISION("attachment-vision"),
        ATTACHMENT_CONTEXT("attachment-context"),
        FINAL_VALIDATION_REPAIR("final-validation-repair"),
        POLISH("polish"),
        STARTER_PREPARED("starter-prepared"),
        IMPLEMENTATION_PLANNING("implementation-planning"),
        BUILD_GATE_NO_CHANGES("build-gate-no-changes"),
        BUILD_GATE_ENTRYPOINT("build-gate-entrypoint"),
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
