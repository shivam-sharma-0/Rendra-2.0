package com.code.pal.agent.intent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.llm.LlmClient;

/** Legacy adapter; RequestRouter owns all routing rules and never invokes a model. */
public class IntentClassifier {
    public IntentClassifier() {}

    public IntentClassifier(LlmClient ignoredClient, AgentProperties ignoredProperties) {
        this();
    }

    public AgentIntent classify(String request) {
        return RequestRouter.route(request) == RequestRouter.Route.CHANGE
                ? AgentIntent.TASK : AgentIntent.CONVERSATIONAL;
    }
}
