package com.code.pal.agent;

import com.code.pal.agent.prompt.PromptLoader;
import org.junit.jupiter.api.Test;

import static com.code.pal.agent.prompt.PromptLoader.Prompt.CONVERSATIONAL;
import static com.code.pal.agent.prompt.PromptLoader.Prompt.MODE_BUILD;
import static com.code.pal.agent.prompt.PromptLoader.Prompt.MODE_CHAT;
import static com.code.pal.agent.prompt.PromptLoader.Prompt.MODE_PLAN;
import static com.code.pal.agent.prompt.PromptLoader.Prompt.SYSTEM_BASE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptPolicyTest {
    @Test
    void smallTalkHasProvenanceAndLanguagePolicyWithoutCopyableExchanges() {
        String prompt = PromptLoader.load(CONVERSATIONAL);
        assertTrue(prompt.contains("Shivam & Team"));
        assertTrue(prompt.contains("Hinglish"));
        assertTrue(prompt.contains("1-4 sentences"));
        assertFalse(prompt.contains("User:"));
        assertFalse(prompt.contains("Rendra:"));
    }

    @Test
    void productExplanationIsAllowedWhilePrivateConfigurationStaysProtected() {
        String prompt = PromptLoader.load(SYSTEM_BASE);
        assertTrue(prompt.contains("product-level"));
        assertTrue(prompt.contains("Shivam & Team"));
        assertTrue(prompt.contains("Never expose secrets"));
        assertFalse(prompt.contains("never discuss how you work internally"));
        assertFalse(prompt.contains("Never mention being an AI"));
    }

    @Test
    void chatOffersAnExplicitBuildActionWithoutChangingMode() {
        String prompt = PromptLoader.load(MODE_CHAT);
        assertTrue(prompt.contains("Build this"));
        assertTrue(prompt.contains("never switch"));
        assertTrue(prompt.contains("do NOT modify"));
    }

    @Test
    void planStatesItsNextStepWithoutImplyingWorkWasApplied() {
        String prompt = PromptLoader.load(MODE_PLAN);
        assertTrue(prompt.contains("next step"));
        assertTrue(prompt.contains("Build this"));
        assertTrue(prompt.contains("Do NOT modify"));
    }

    @Test
    void buildHasNoOrphanedSummaryInstruction() {
        assertFalse(PromptLoader.load(MODE_BUILD).contains("\nin that same response."));
    }

    @Test
    void unusedIntentModelPromptIsNotPackaged() {
        assertNull(getClass().getClassLoader().getResource("prompts/intent-classifier.md"));
    }
}
