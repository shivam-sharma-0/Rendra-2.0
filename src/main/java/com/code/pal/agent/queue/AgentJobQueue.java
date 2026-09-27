package com.code.pal.agent.queue;

import com.code.pal.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Redis-backed durable run queue and ownership-checked per-project lock. */
@Component
@RequiredArgsConstructor
public class AgentJobQueue {
    private static final String QUEUE_KEY = "agent:queue";
    private static final String LOCK_PREFIX = "agent:lock:";
    private static final String CANCEL_PREFIX = "agent:cancel:";
    private static final DefaultRedisScript<Long> UNLOCK_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public void enqueue(UUID runId) { redis.opsForList().leftPush(QUEUE_KEY, runId.toString()); }
    public void requeue(UUID runId) { redis.opsForList().leftPush(QUEUE_KEY, runId.toString()); }

    public UUID dequeue(Duration timeout) {
        String id = redis.opsForList().rightPop(QUEUE_KEY, timeout);
        return id == null ? null : UUID.fromString(id);
    }

    /**
     * Acquire the project's lock with a short lease. A live worker renews it every heartbeat; if the
     * worker dies, the lease expires within {@link AgentProperties#leaseMs()} so the run can be recovered
     * quickly â€” without ever letting a merely-slow run be reclaimed.
     */
    public boolean tryLock(UUID projectId, UUID runId) {
        Duration ttl = Duration.ofMillis(props.leaseMs());
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + projectId, runId.toString(), ttl));
    }

    /** Extend the lease only while this worker still owns the lock (fencing against a stale renewal). */
    public void renewLock(UUID projectId, UUID runId) {
        redis.execute(RENEW_IF_OWNER, List.of(LOCK_PREFIX + projectId), runId.toString(), String.valueOf(props.leaseMs()));
    }

    /** True while some worker holds this project's lock â€” used as a secondary "worker alive" guard. */
    public boolean isLocked(UUID projectId) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + projectId));
    }

    /** Delete only a lock still owned by this worker; never release a replacement lock after TTL expiry. */
    public void unlock(UUID projectId, UUID runId) {
        redis.execute(UNLOCK_IF_OWNER, List.of(LOCK_PREFIX + projectId), runId.toString());
    }

    /** Flag a run for cancellation; the executing worker sees it and stops at the next checkpoint. */
    public void requestCancel(UUID runId) {
        redis.opsForValue().set(CANCEL_PREFIX + runId, "1", Duration.ofMillis(props.getRunTimeoutMs() + 60_000));
    }

    public boolean isCancelRequested(UUID runId) {
        return Boolean.TRUE.equals(redis.hasKey(CANCEL_PREFIX + runId));
    }

    public void clearCancel(UUID runId) {
        redis.delete(CANCEL_PREFIX + runId);
    }
}
