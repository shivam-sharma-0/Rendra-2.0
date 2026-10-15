package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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

    private com.code.pal.agent.lease.ProjectLeaseService leases;
    @org.springframework.beans.factory.annotation.Autowired
    void useLeases(com.code.pal.agent.lease.ProjectLeaseService leases) { this.leases=leases; }
    private final AgentRunRepository runs;
    private final AgentJobQueue queue;
    private final AgentProperties props;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService scheduler;

    public AgentHeartbeat(AgentRunRepository runs, AgentJobQueue queue, AgentProperties props,
                          PlatformTransactionManager txManager) {
        this.runs = runs;
        this.queue = queue;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    /** Begin heartbeating a run; the returned handle stops it (idempotent) and is safe to call in a finally. */
    public AutoCloseable start(UUID runId, UUID projectId) {
        long interval = Math.max(2_000, props.getHeartbeatIntervalMs());
        var fence=com.code.pal.agent.lease.RunFence.current();
        var cancellation=com.code.pal.agent.runtime.RunCancellation.current();
        ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(
                () -> beat(runId, projectId,fence,cancellation), interval, interval, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    void beat(UUID runId, UUID projectId,com.code.pal.agent.lease.RunFence fence,com.code.pal.agent.runtime.RunCancellation cancellation) {
        try {
            UUID owner=fence==null?runId:fence.ownerId();
            if(!queue.renewLock(projectId,owner) || (leases!=null && (fence==null || !leases.renew(fence))))
                throw new com.code.pal.agent.lease.LeaseLostException();
            if(leases==null) tx.executeWithoutResult(status -> runs.touchHeartbeat(runId,Instant.now()));
            // Durable renewal updates heartbeat atomically without waiting for long filesystem guards.
        } catch (RuntimeException e) {
            cancellation.cancel();
            // Ownership uncertainty stops work; recovery handles durable state after expiry.
            log.warn("Worker ownership renewal failed runId={} errorType={}",runId,e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
