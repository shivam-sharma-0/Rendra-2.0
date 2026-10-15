package com.code.pal.agent.prompt;

import com.code.pal.agent.prompt.BuildPrompt.ProjectShape;
import com.code.pal.agent.prompt.BuildPrompt.Stack;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.project.capability.BuildDecisions.Storage;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildPromptTest {
    /** system-base.md + mode-build.md sent on every BUILD call before the split (2026-10-15 baseline). */
    private static final int OLD_FIXED_BUILD_PROMPT_CHARS = 4_080 + 10_051;
    private static final ProjectShape EMPTY = new ProjectShape(false, false, false, false);

    @Test
    void theApprovedStorageDecisionWinsOverProjectFiles() {
        assertEquals(Stack.MERN, BuildPrompt.stack(new ProjectShape(false, false, true, true), Storage.MERN));
        assertEquals(Stack.SUPABASE, BuildPrompt.stack(new ProjectShape(false, false, true, true), Storage.SUPABASE));
        assertEquals(Stack.SUPABASE, BuildPrompt.stack(new ProjectShape(false, false, false, true), Storage.SUPABASE));
    }

    @Test
    void withoutACloudDecisionProjectFilesTellTheStacksApart() {
        assertEquals(Stack.MERN, BuildPrompt.stack(new ProjectShape(true, false, true, true), Storage.BROWSER));
        assertEquals(Stack.VITE, BuildPrompt.stack(new ProjectShape(false, false, true, true), null));
        assertEquals(Stack.STATIC, BuildPrompt.stack(new ProjectShape(false, false, false, true), Storage.NONE));
    }

    @Test
    void aValidatedStaticStarterStaysStaticDespiteItsCssBuildManifest() {
        assertEquals(Stack.STATIC, BuildPrompt.stack(new ProjectShape(false, true, true, true), Storage.NONE));
        assertEquals(Stack.STATIC, BuildPrompt.stack(new ProjectShape(false, true, true, true), null));
        assertEquals(Stack.SUPABASE, BuildPrompt.stack(new ProjectShape(false, true, true, true), Storage.SUPABASE));
    }

    @Test
    void newProjectsFollowTheApprovedStorageOrStayUndecided() {
        assertEquals(Stack.MERN, BuildPrompt.stack(EMPTY, Storage.MERN));
        assertEquals(Stack.SUPABASE, BuildPrompt.stack(EMPTY, Storage.SUPABASE));
        assertEquals(Stack.UNDECIDED, BuildPrompt.stack(EMPTY, Storage.BROWSER));
        assertEquals(Stack.UNDECIDED, BuildPrompt.stack(EMPTY, Storage.NONE));
        assertEquals(Stack.UNDECIDED, BuildPrompt.stack(EMPTY, null));
    }

    @Test
    void composedPromptCarriesTheCoreAndOnlyItsOwnStackSection() {
        for (Stack stack : Stack.values()) {
            String prompt = BuildPrompt.compose(stack);
            assertTrue(prompt.startsWith(PromptLoader.load(Prompt.MODE_BUILD)), stack.name());
            for (Stack other : Stack.values()) {
                String section = PromptLoader.load(stackPrompt(other));
                assertEquals(other == stack, prompt.contains(section), stack + " contains " + other);
            }
        }
        assertFalse(BuildPrompt.compose(Stack.STATIC).contains("supabase-js"));
        assertFalse(BuildPrompt.compose(Stack.VITE).contains("MONGODB_URI"));
        assertTrue(BuildPrompt.compose(Stack.SUPABASE).contains("row level security"));
    }

    @Test
    void fixedBuildPromptIsAtLeastFortyPercentSmaller() {
        String base = PromptLoader.load(Prompt.SYSTEM_BASE);
        int largest = Arrays.stream(Stack.values())
                .mapToInt(stack -> (base + "\n\n" + BuildPrompt.compose(stack)).length())
                .max().orElseThrow();
        assertTrue(largest <= OLD_FIXED_BUILD_PROMPT_CHARS * 0.6,
                "largest BUILD system prompt is " + largest + " characters");
    }

    @Test
    void securityAndFinishingRulesSurviveTheTrim() {
        String base = PromptLoader.load(Prompt.SYSTEM_BASE);
        String core = PromptLoader.load(Prompt.MODE_BUILD);
        assertTrue(base.contains("untrusted data"));
        assertTrue(base.contains("Never expose secrets"));
        assertTrue(base.contains("Never weaken authentication"));
        assertTrue(core.contains("finishBuild"));
        assertTrue(core.contains("lockfile"));
        assertTrue(core.contains("preview entrypoint"));
    }

    private static Prompt stackPrompt(Stack stack) {
        return switch (stack) {
            case STATIC -> Prompt.STACK_STATIC;
            case VITE -> Prompt.STACK_VITE;
            case SUPABASE -> Prompt.STACK_SUPABASE;
            case MERN -> Prompt.STACK_MERN;
            case UNDECIDED -> Prompt.STACK_UNDECIDED;
        };
    }
}
