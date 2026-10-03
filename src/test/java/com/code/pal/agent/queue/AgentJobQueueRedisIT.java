package com.code.pal.agent.queue;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The park/release Lua scripts against a real Redis. Opt-in; uses database 15 so the development
 * queue in database 0 is never touched: {@code mvnw test -Dtest=AgentJobQueueRedisIT -Dcodepal.redis.it=true}
 */
@EnabledIfSystemProperty(named = "codepal.redis.it", matches = "true")
class AgentJobQueueRedisIT {

    private LettuceConnectionFactory connections;
    private StringRedisTemplate redis;
    private AgentJobQueue queue;
    private final UUID projectId = UUID.randomUUID();

    @BeforeEach
    void connect() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                System.getProperty("codepal.redis.host", "127.0.0.1"), 6379);
        config.setDatabase(15);
        connections = new LettuceConnectionFactory(config);
        connections.afterPropertiesSet();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
        redis.delete(List.of("agent:queue", "agent:lock:" + projectId, "agent:parked:" + projectId));
        queue = new AgentJobQueue(redis, new AgentProperties());
    }

    @AfterEach
    void close() {
        redis.delete(List.of("agent:queue", "agent:lock:" + projectId, "agent:parked:" + projectId));
        connections.destroy();
    }

    @Test
    void runWaitsWhileProjectIsBusyAndReturnsInOrderWhenReleased() {
        UUID holder = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(queue.tryLock(projectId, holder));

        assertTrue(queue.parkBehindLock(projectId, first));
        assertTrue(queue.parkBehindLock(projectId, second));
        assertEquals(0L, redis.opsForList().size("agent:queue"));

        assertEquals(2, queue.unlockAndReleaseParked(projectId, holder));

        assertFalse(queue.isLocked(projectId));
        assertEquals(first, queue.dequeue(java.time.Duration.ofSeconds(1)), "parked runs keep their order");
        assertEquals(second, queue.dequeue(java.time.Duration.ofSeconds(1)));
    }

    @Test
    void parkingAfterTheLockWasReleasedGoesStraightBackToTheQueue() {
        UUID run = UUID.randomUUID();

        assertFalse(queue.parkBehindLock(projectId, run));   // nobody holds the lock any more

        assertEquals(run, queue.dequeue(java.time.Duration.ofSeconds(1)));
    }

    @Test
    void orphanedParkedRunsAreReleasedOnlyOnceTheLockIsGone() {
        UUID holder = UUID.randomUUID(), waiting = UUID.randomUUID();
        queue.tryLock(projectId, holder);
        queue.parkBehindLock(projectId, waiting);

        assertEquals(0, queue.releaseOrphanedParked(), "a live lock means its worker will release them");

        redis.delete("agent:lock:" + projectId);             // the holder's worker died; lease expired
        assertEquals(1, queue.releaseOrphanedParked());
        assertEquals(waiting, queue.dequeue(java.time.Duration.ofSeconds(1)));
    }

    @Test
    void releasingDoesNotDeleteALockSomeoneElseNowOwns() {
        UUID expiredHolder = UUID.randomUUID(), newHolder = UUID.randomUUID();
        queue.tryLock(projectId, newHolder);

        queue.unlockAndReleaseParked(projectId, expiredHolder);

        assertTrue(queue.isLocked(projectId));
        assertEquals(newHolder.toString(), redis.opsForValue().get("agent:lock:" + projectId));
    }
}
