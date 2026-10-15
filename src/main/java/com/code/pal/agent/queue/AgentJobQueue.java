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
    private static final String PARKED_PREFIX = "agent:parked:";
    /**
     * Park a run behind its busy project, or, if the lock was released in the meantime, put it straight
     * back on the queue. Atomic with {@link #UNLOCK_AND_RELEASE}, so a run can never be parked after
     * the release that should have picked it up.
     */
    private static final DefaultRedisScript<Long> PARK_IF_LOCKED = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then redis.call('lpush', KEYS[2], ARGV[1]) return 1 "
                    + "else redis.call('lpush', KEYS[3], ARGV[1]) return 0 end", Long.class);
    /** Delete the lock if still owned, then move every parked run of the project back onto the queue. */
    private static final DefaultRedisScript<Long> UNLOCK_AND_RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]) end "
                    + "local moved = 0 "
                    + "while redis.call('rpoplpush', KEYS[2], KEYS[3]) do moved = moved + 1 end "
                    + "return moved", Long.class);
    /** Release a parked list whose project is no longer locked (its worker died before releasing). */
    private static final DefaultRedisScript<Long> RELEASE_IF_UNLOCKED = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return 0 end "
                    + "local moved = 0 "
                    + "while redis.call('rpoplpush', KEYS[2], KEYS[3]) do moved = moved + 1 end "
                    + "return moved", Long.class);

    private final StringRedisTemplate redis;
    private final AgentProperties props;

    public void enqueue(UUID runId) { redis.opsForList().leftPush(QUEUE_KEY, runId.toString()); }

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
    public boolean renewLock(UUID projectId, UUID runId) {
        return Long.valueOf(1).equals(redis.execute(RENEW_IF_OWNER, List.of(LOCK_PREFIX + projectId), runId.toString(), String.valueOf(props.leaseMs())));
    }

    /** True while some worker holds this project's lock â€” used as a secondary "worker alive" guard. */
    public boolean isLocked(UUID projectId) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + projectId));
    }

    /** Delete only a lock still owned by this worker; never release a replacement lock after TTL expiry. */
    public void unlock(UUID projectId, UUID runId) {
        redis.execute(UNLOCK_IF_OWNER, List.of(LOCK_PREFIX + projectId), runId.toString());
    }

    /**
     * Instead of re-polling a busy project, wait in its parked list until the running turn finishes.
     * @return true if parked, false if the project was free by now and the run went back on the queue
     */
    public boolean parkBehindLock(UUID projectId, UUID runId) {
        Long parked = redis.execute(PARK_IF_LOCKED,
                List.of(LOCK_PREFIX + projectId, PARKED_PREFIX + projectId, QUEUE_KEY), runId.toString());
        return parked != null && parked == 1;
    }

    /** Release the project lock (if still owned) and requeue the runs that were waiting for it. */
    public long unlockAndReleaseParked(UUID projectId, UUID runId) {
        Long moved = redis.execute(UNLOCK_AND_RELEASE,
                List.of(LOCK_PREFIX + projectId, PARKED_PREFIX + projectId, QUEUE_KEY), runId.toString());
        return moved == null ? 0 : moved;
    }

    /** Requeue parked runs of projects that are no longer locked (their worker died before releasing). */
    public long releaseOrphanedParked() {
        long moved = 0;
        var options = org.springframework.data.redis.core.ScanOptions.scanOptions().match(PARKED_PREFIX + "*").count(100).build();
        try (var keys = redis.scan(options)) {
            while (keys.hasNext()) {
                String parkedKey = keys.next();
                String projectId = parkedKey.substring(PARKED_PREFIX.length());
                Long released = redis.execute(RELEASE_IF_UNLOCKED,
                        List.of(LOCK_PREFIX + projectId, parkedKey, QUEUE_KEY));
                moved += released == null ? 0 : released;
            }
        }
        return moved;
    }

    /** Flag a run for cancellation; the executing worker sees it and stops at the next checkpoint. */
    public void requestCancel(UUID runId) {
        redis.opsForValue().set(CANCEL_PREFIX + runId, "1", Duration.ofMillis(props.longestRunTimeoutMs() + 60_000));
    }

    public boolean isCancelRequested(UUID runId) {
        return Boolean.TRUE.equals(redis.hasKey(CANCEL_PREFIX + runId));
    }

    public void clearCancel(UUID runId) {
        redis.delete(CANCEL_PREFIX + runId);
    }
}
