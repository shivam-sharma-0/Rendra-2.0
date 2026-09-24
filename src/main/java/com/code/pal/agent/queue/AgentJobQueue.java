package com.code.pal.agent.queue;

import com.code.pal.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis-backed run queue + per-project lock.
 *
 * <p>A Redis list is the job queue (survives restarts, and lets the worker move to a separate
 * service later). The per-project lock ({@code SET NX EX}) serialises runs so two turns on the same
 * project never execute concurrently — important for collaboration (multiple users, one project).
 */
@Component
@RequiredArgsConstructor
public class AgentJobQueue {

    private static final String QUEUE_KEY = "agent:queue";
    private static final String LOCK_PREFIX = "agent:lock:";

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public void enqueue(UUID runId) {
        redis.opsForList().leftPush(QUEUE_KEY, runId.toString());
    }

    /** Put a run back at the head to retry shortly (e.g. its project is currently locked). */
    public void requeue(UUID runId) {
        redis.opsForList().leftPush(QUEUE_KEY, runId.toString());
    }

    /** Block up to {@code timeout} for the next run id, or null if none arrived. */
    public UUID dequeue(Duration timeout) {
        String id = redis.opsForList().rightPop(QUEUE_KEY, timeout);
        return id == null ? null : UUID.fromString(id);
    }

    /** Acquire the project's run lock; false if another run holds it. */
    public boolean tryLock(UUID projectId, UUID runId) {
        Duration ttl = Duration.ofMillis(props.getRunTimeoutMs() + 30_000);
        Boolean ok = redis.opsForValue().setIfAbsent(LOCK_PREFIX + projectId, runId.toString(), ttl);
        return Boolean.TRUE.equals(ok);
    }

    public void unlock(UUID projectId) {
        redis.delete(LOCK_PREFIX + projectId);
    }
}
