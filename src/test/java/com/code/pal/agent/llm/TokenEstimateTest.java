package com.code.pal.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimateTest {

    @Test
    void anImageCountsAsAFixedAmountNotItsDataUrlLength() {
        String dataUrl = "data:image/jpeg;base64," + "A".repeat(200_000);
        long estimate = TokenEstimate.input(List.of(LlmClient.LlmMessage.userWithImages("Describe it", List.of(dataUrl))), 0);
        assertTrue(estimate >= TokenEstimate.TOKENS_PER_IMAGE && estimate < TokenEstimate.TOKENS_PER_IMAGE + 50, "estimate " + estimate);
    }

    @Test
    void textIsAboutFourCharactersPerToken() {
        long estimate = TokenEstimate.input(List.of(LlmClient.LlmMessage.user("x".repeat(4_000))), 0);
        assertTrue(estimate >= 1_000 && estimate <= 1_010, "estimate " + estimate);
    }
}
