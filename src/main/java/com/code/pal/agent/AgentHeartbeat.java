package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.runtime.RunCancellation;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a running agent's liveness fresh. While a worker executes a run, it periodically (a) bumps the
 * run's {@code heartbeat_at} and (b) renews the project lock lease. This lets {@code AgentRunRecovery}
 * distinguish a genuinely dead worker (heartbeat stops) from one merely busy inside a long LLM stream â€”
 * so a live run is never marked failed and rolled back from under itself.
 *
 * <p>A single shared daemon scheduler serves every concurrent run; each {@link #start} returns a handle
 * the worker closes when the run ends.
 */
@Slf4j
@Component
public class AgentHeartbeat {

    private final ProjectLeaseService leases;
    private final AgentJobQueue queue;
    private final AgentProperties props;
    private final ScheduledExecutorService scheduler;

    public AgentHeartbeat(AgentJobQueue queue, AgentProperties props, ProjectLeaseService leases) {
        this.leases = leases;
        this.queue = queue;
        this.props = props;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** Begin heartbeating a run; the returned handle stops it (idempotent) and is safe to call in a finally. */
    public AutoCloseable start(UUID runId, UUID projectId) {
        long interval = Math.max(2_000, props.getHeartbeatIntervalMs());
        RunFence fence = RunFence.current();
        RunCancellation cancellation = RunCancellation.current();
        var lastRenewed = new AtomicLong(nanoTime());
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> beat(runId, projectId, fence, cancellation, lastRenewed), interval, interval, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    /** One renewal probe; scheduled runs retain their renewal state in start(). */
    void beat(UUID runId, UUID projectId, RunFence fence, RunCancellation cancellation) {
        beat(runId, projectId, fence, cancellation, new AtomicLong(nanoTime()));
    }

    private void beat(UUID runId, UUID projectId, RunFence fence, RunCancellation cancellation, AtomicLong lastRenewed) {
        long renewalStarted = nanoTime();
        try {
            if (fence == null || !runId.equals(fence.runId()) || !projectId.equals(fence.projectId())
                    || !leases.renew(fence)) {
                cancellation.loseOwnership();
                return;
            }
            // Use the attempt's start, so database latency cannot extend the known lease lifetime.
            lastRenewed.set(renewalStarted);
        } catch (RuntimeException unavailable) {
            long interval = Math.max(2_000, props.getHeartbeatIntervalMs());
            long graceNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, props.leaseMs() - interval));
            boolean expired = nanoTime() - lastRenewed.get() >= graceNanos;
            if (expired) {
                cancellation.loseOwnership();
            }
            log.warn("Worker database renewal deferred runId={} ownershipExpired={} errorType={}",
                    runId, expired, unavailable.getClass().getSimpleName());
            return;
        }

        boolean renewed = false;
        try {
            renewed = queue.renewLock(projectId, fence.ownerId());
        } catch (RuntimeException unavailable) {
            log.warn("Redis lock renewal deferred runId={} errorType={}",
                    runId, unavailable.getClass().getSimpleName());
        }
        if (!renewed) {
            try {
                queue.tryLock(projectId, fence.ownerId());
            } catch (RuntimeException unavailable) {
                log.warn("Redis lock reacquisition deferred runId={} errorType={}",
                        runId, unavailable.getClass().getSimpleName());
            }
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    protected long nanoTime() {
        return System.nanoTime();
    }
}
