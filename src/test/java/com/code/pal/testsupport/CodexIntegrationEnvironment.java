package com.code.pal.testsupport;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Path;
import java.util.Map;

/** Loads only test config, with no application scanning, workers or inherited credentials. */
final class CodexIntegrationEnvironment {
    private CodexIntegrationEnvironment() {}

    static GenericApplicationContext load(String project, int postgresPort, int redisPort) {
        if (project == null || !project.matches("codepal-codex-it-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("A disposable Compose project id is required");
        }
        requirePort(postgresPort, 5432);
        requirePort(redisPort, 6379);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("codex-test-inputs", Map.of(
                "spring.config.location", "classpath:application-codex-it.properties",
                "spring.profiles.active", "codex-it",
                "codex.it.project", project,
                "codex.it.postgres.port", postgresPort,
                "codex.it.redis.port", redisPort,
                "user.dir", Path.of("").toAbsolutePath().toString())));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        try {
            new ConfigDataApplicationContextInitializer().initialize(context);
            context.refresh();
            return context;
        } catch (RuntimeException failure) {
            context.close();
            throw failure;
        }
    }

    private static void requirePort(int port, int ordinaryServicePort) {
        if (port < 1024 || port > 65535 || port == ordinaryServicePort) {
            throw new IllegalArgumentException("An ephemeral disposable service port is required");
        }
    }
}
