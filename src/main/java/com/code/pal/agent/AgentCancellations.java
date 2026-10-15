package com.code.pal.agent;

import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.runtime.RunCancellation;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Stop signals of the runs executing in this process. Stop requests travel through the queue's
 * Redis flag (any instance may receive the HTTP request); this registry turns that flag into an
 * immediate local signal: {@link #cancelLocal} for a request handled here, and a light poll every
 * {@value #POLL_MS} ms for one handled elsewhere.
 */
@Slf4j
@Component
public class AgentCancellations {

    static final long POLL_MS = 250;

    private final AgentJobQueue queue;
    private final com.code.pal.agent.repository.AgentRunRepository runs;
    private final Map<UUID, RunCancellation> active = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> poller;

    public AgentCancellations(AgentJobQueue queue) { this(queue,null); }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentCancellations(AgentJobQueue queue,com.code.pal.agent.repository.AgentRunRepository runs) {
        this.queue = queue;
        this.runs=runs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-cancel-poll");
            t.setDaemon(true);
            return t;
        });
    }

    /** Starts tracking a run; returns its cancellation (already cancelled when Stop came while it was queued). */
    public RunCancellation register(UUID runId) {
        RunCancellation cancellation = active.computeIfAbsent(runId, RunCancellation::new);
        startPolling();
        if (cancelRequested(runId)) cancellation.cancel();
        return cancellation;
    }

    public void unregister(UUID runId) {
        active.remove(runId);
    }

    /** Cancels a run executing in this process at once; false when it is not running here. */
    public boolean cancelLocal(UUID runId) {
        RunCancellation cancellation = active.get(runId);
        if (cancellation == null) return false;
        cancellation.cancel();
        return true;
    }

    /** One poll: cancels every tracked run whose Stop flag is set. Package-private for tests. */
    void pollOnce() {
        for (Map.Entry<UUID, RunCancellation> entry : active.entrySet()) {
            if (entry.getValue().isCancelled()) continue;
            try {
                if (cancelRequested(entry.getKey())) entry.getValue().cancel();
            } catch (RuntimeException e) {
                log.debug("Cancel poll failed for run {}: {}", entry.getKey(), e.getMessage());
            }
        }
    }

    private boolean cancelRequested(UUID id) {
        if(runs!=null && runs.findById(id).map(r->r.getCancelRequestedAt()!=null).orElse(false)) return true;
        return queue.isCancelRequested(id);
    }

    private void startPolling() {
        if (poller != null) return;
        synchronized (this) {
            if (poller == null) poller = scheduler.scheduleWithFixedDelay(this::pollOnce, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
