package com.code.pal.agent.intent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnclearReplyTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n{\"reply\":\"Add dark mode.\",\"action\":\"build\"}\n```",
            "My answer follows:\n{\"reply\":\"Add dark mode.\",\"action\":\"build\"}\nThanks!",
            "{\"reply\":\"Add dark mode.\",\"action\":\"build\"}"
    })
    void extractsOneObjectFromFencesAndSurroundingProse(String response) {
        assertEquals(new UnclearReply("Add dark mode.", "build"), UnclearReply.decode(mapper, response, "hmm"));
    }

    @Test
    void twoObjectsAreAmbiguousAndNeverAuthorizeAnOffer() {
        String response = "{\"reply\":\"Explain.\",\"action\":\"answer\"} "
                + "{\"reply\":\"Delete it.\",\"action\":\"build\"}";
        assertEquals("ask", UnclearReply.decode(mapper, response, "hmm").action());
    }

    @Test
    void escapedQuotesAndBracesInsideReplyRemainData() {
        String response = "Before {\"reply\":\"Use {color} and \\\"blue\\\".\",\"action\":\"answer\"} After";
        assertEquals("Use {color} and \"blue\".", UnclearReply.decode(mapper, response, "hmm").reply());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[{\"reply\":\"Add dark mode.\",\"action\":\"build\"}]",
            "```json\n[{\"reply\":\"Add dark mode.\",\"action\":\"build\"}]\n```"})
    void arraysNeverBecomeAnOfferByExtractingTheirNestedObject(String response) {
        assertEquals("ask", UnclearReply.decode(mapper, response, "hmm").action());
    }
}
