package com.code.pal.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Surfaces missing critical secrets at boot instead of on first use.
 *
 * <p>Without this, a blank {@code GOOGLE_CLIENT_SECRET} only manifests as a confusing
 * "client_secret is missing" the first time someone tries Google login, and a blank
 * {@code AGENT_LLM_API_KEY} only fails when the first agent run calls the LLM. Here we log a loud
 * WARN once at startup so the gap is obvious. We warn rather than fail so local development (where
 * these may legitimately be unset) is never blocked.
 */
@Slf4j
@Component
public class StartupSecretsValidator {

    @Value("${spring.security.oauth2.client.registration.google.client-secret:}")
    private String googleClientSecret;

    @Value("${app.agent.llm.api-key:}")
    private String agentLlmApiKey;

    @EventListener(ApplicationReadyEvent.class)
    public void validate() {
        warnIfBlank(googleClientSecret, "GOOGLE_CLIENT_SECRET",
                "Google sign-in will fail with 'client_secret is missing'.");
        warnIfBlank(agentLlmApiKey, "AGENT_LLM_API_KEY",
                "Agent runs will fail when calling the LLM provider.");
    }

    private void warnIfBlank(String value, String name, String consequence) {
        if (value == null || value.isBlank()) {
            log.warn("Config: {} is not set. {} Provide it via env or .secrets/secrets.properties.", name, consequence);
        }
    }
}
