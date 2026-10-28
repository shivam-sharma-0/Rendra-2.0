package com.code.pal.agent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPropertiesDevRouteTest {
    @Test void theCodexRouteSurvivesRoutingSanitizationOnlyWhenTheDeveloperSwitchIsOn() {
        // Live E2E 2026-10-28: startup removed codex:* as an unconfigured provider, so units silently used Codestral.
        for (boolean enabled : List.of(true, false)) {
            var properties = new AgentProperties();
            var mistral = new AgentProperties.Provider();
            mistral.setApiKey("fixture-key");
            properties.getProviders().put("mistral", mistral);
            properties.getModels().setFreeBuild(List.of("codex:gpt-5.6-luna", "mistral:codestral-latest"));
            properties.getDev().setCodexEnabled(enabled);
            properties.decryptKeys();
            assertEquals(enabled ? List.of("codex:gpt-5.6-luna", "mistral:codestral-latest") : List.of("mistral:codestral-latest"),
                    properties.getModels().getFreeBuild());
        }
    }
}
