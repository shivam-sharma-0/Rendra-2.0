package com.code.pal.testsupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CodexIntegrationEnvironmentTest {
    @Test
    void loadsOnlyTestConfigAndDoesNotInheritOrdinaryCredentials() {
        String previous = System.getProperty("spring.datasource.url");
        System.setProperty("spring.datasource.url", "jdbc:postgresql://ordinary.invalid/normal_app");
        try (var context = CodexIntegrationEnvironment.load("codepal-codex-it-012345abcdef", 25432, 26379)) {
            var environment = context.getEnvironment();
            assertEquals("codepal-codex-disposable", environment.getProperty("codex.it.marker"));
            assertArrayEquals(new String[]{"codex-it"}, environment.getActiveProfiles());
            assertEquals("jdbc:postgresql://127.0.0.1:25432/codepal_codex_it",
                    environment.getProperty("spring.datasource.url"));
            assertEquals("26379", environment.getProperty("spring.data.redis.port"));
            assertEquals("http://127.0.0.1:1", environment.getProperty("app.agent.llm.base-url"));
            assertNull(environment.getProperty("GATEWAY_MASTER_KEY"));
            assertEquals(0, context.getBeanDefinitionCount(), "No app beans or worker threads should start");
        } finally {
            if (previous == null) System.clearProperty("spring.datasource.url");
            else System.setProperty("spring.datasource.url", previous);
        }
    }

    @Test
    void refusesOrdinaryServicePortsAndMissingOwnership() {
        assertThrows(IllegalArgumentException.class, () -> CodexIntegrationEnvironment.load("ordinary", 25432, 26379));
        assertThrows(IllegalArgumentException.class,
                () -> CodexIntegrationEnvironment.load("codepal-codex-it-012345abcdef", 5432, 26379));
        assertThrows(IllegalArgumentException.class,
                () -> CodexIntegrationEnvironment.load("codepal-codex-it-012345abcdef", 25432, 6379));
    }
}
