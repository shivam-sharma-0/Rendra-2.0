package com.code.pal.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Subsystem smoke checks only; use the dedicated Compose runner, never normal app startup. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
class DisposableServicesIT {
    private org.springframework.context.support.GenericApplicationContext configuration() {
        return CodexIntegrationEnvironment.load(System.getProperty("codex.it.project"),
                Integer.parseInt(System.getProperty("codex.it.postgres.port", "0")),
                Integer.parseInt(System.getProperty("codex.it.redis.port", "0")));
    }

    @Test
    void postgresHasDisposableIdentityAndRollsBackATransaction() throws Exception {
        try (var context = configuration()) {
            var env = context.getEnvironment();
            Properties credentials = new Properties();
            credentials.setProperty("user", env.getRequiredProperty("spring.datasource.username"));
            credentials.setProperty("password", env.getRequiredProperty("spring.datasource.password"));
            credentials.setProperty("connectTimeout", "5");
            credentials.setProperty("socketTimeout", "10");
            try (var connection = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"), credentials);
                 var statement = connection.createStatement()) {
                try (var identity = statement.executeQuery("select current_database(), current_user")) {
                    assertTrue(identity.next());
                    assertEquals("codepal_codex_it", identity.getString(1));
                    assertEquals("codepal_codex_it", identity.getString(2));
                }
                statement.execute("create temporary table codex_rollback_probe (value integer)");
                connection.setAutoCommit(false);
                statement.execute("insert into codex_rollback_probe values (1)");
                connection.rollback();
                try (var count = statement.executeQuery("select count(*) from codex_rollback_probe")) {
                    assertTrue(count.next());
                    assertEquals(0, count.getInt(1));
                }
            }
        }
    }

    @Test
    void redisCanUseAndRemoveAnIsolatedExpiringKey() {
        try (var context = configuration()) {
            var env = context.getEnvironment();
            var config = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),
                    Integer.parseInt(env.getRequiredProperty("spring.data.redis.port")));
            var client = org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder()
                    .commandTimeout(Duration.ofSeconds(5)).shutdownTimeout(Duration.ofSeconds(1)).build();
            var factory = new LettuceConnectionFactory(config, client);
            factory.afterPropertiesSet();
            var redis = new StringRedisTemplate(factory);
            redis.afterPropertiesSet();
            String key = "codex:it:" + env.getRequiredProperty("codex.it.run-id") + ":" + UUID.randomUUID();
            try {
                redis.opsForValue().set(key, "isolated", Duration.ofSeconds(30));
                assertEquals("isolated", redis.opsForValue().get(key));
                assertTrue(redis.getExpire(key) > 0);
                assertEquals(Boolean.TRUE, redis.delete(key));
                assertEquals(Boolean.FALSE, redis.hasKey(key));
            } finally {
                try { redis.delete(key); } finally { factory.destroy(); }
            }
        }
    }
}
