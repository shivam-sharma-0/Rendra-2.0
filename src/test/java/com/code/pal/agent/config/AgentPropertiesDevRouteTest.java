package com.code.pal.agent.config;

import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentPropertiesDevRouteTest {
    @Test void codexAllPutsCodexFirstOnEveryTextRouteAndKeepsTheConfiguredModelsAsFallbacks() {
        var properties = new AgentProperties();
        var mistral = new AgentProperties.Provider();
        mistral.setApiKey("fixture-key");
        properties.getProviders().put("mistral", mistral);
        properties.getModels().setFreeBuild(List.of("mistral:codestral-latest"));
        properties.getModels().setFreeChat(List.of("mistral:ministral-8b-latest"));
        properties.getModels().setFreePlan(List.of("codex:gpt-5.6-sol", "mistral:codestral-latest"));
        properties.getRequirements().setModels(List.of("mistral:codestral-latest"));
        properties.getDev().setCodexAll(true);
        properties.decryptKeys();
        assertEquals(List.of("codex:gpt-5.6-sol", "mistral:codestral-latest"), properties.getModels().getFreeBuild());
        assertEquals(List.of("codex:gpt-5.6-sol", "mistral:ministral-8b-latest"), properties.getModels().getFreeChat());
        assertEquals(List.of("codex:gpt-5.6-sol", "mistral:codestral-latest"), properties.getModels().getFreePlan(), "no duplicate");
        assertEquals(new AgentProperties.ModelSelection("codex", "gpt-5.6-sol"), properties.requirementsRoutes().getFirst());
        assertEquals(new AgentProperties.ModelSelection("codex", "gpt-5.6-sol"), properties.preferredModel(ProjectType.CHAT));
    }

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
            if (enabled) {
                // Attempt 24: an unparsed "codex:" prefix became part of the model id, so no accounting bound matched.
                properties.setBillingMode(AgentProperties.BillingMode.FREE);
                assertEquals(new AgentProperties.ModelSelection("codex", "gpt-5.6-luna"),
                        properties.preferredModel(ProjectType.BUILD));
            }
        }
    }
}
