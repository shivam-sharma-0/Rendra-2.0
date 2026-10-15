package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.runtime.RunCancellation;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> beat(runId, projectId,fence,cancellation), interval, interval, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    void beat(UUID runId, UUID projectId, RunFence fence, RunCancellation cancellation) {
        try {
            if (fence == null || !runId.equals(fence.runId()) || !projectId.equals(fence.projectId())
                    || !leases.renew(fence)) {
                cancellation.loseOwnership();
                return;
            }
        } catch (RuntimeException unavailable) {
            cancellation.loseOwnership();
            log.warn("Worker database ownership unavailable runId={} errorType={}",
                    runId, unavailable.getClass().getSimpleName());
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
}
