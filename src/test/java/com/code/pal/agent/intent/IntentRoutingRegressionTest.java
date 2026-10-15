package com.code.pal.agent.intent;

import com.code.pal.agent.domain.AgentIntent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRoutingRegressionTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "can u tell how u work?",
            "how does this app work?",
            "login page samjhao",
            "kya tum database bana sakte ho?",
            "> add dark mode\n\nWhat does this mean?",
            "Hello, I hope you are having a lovely day. I just wanted to say thank you for your help!",
            "What is the purpose of src/App.tsx?",
            "haan"
    })
    void explanationAndUnboundRepliesCannotAuthorizeABuild(String request) {
        assertEquals(AgentIntent.CONVERSATIONAL, new IntentClassifier().classify(request));
    }
}
