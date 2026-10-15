package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.config.SandboxPolicy;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pulls runs off the queue and executes them. A fixed pool of consumer threads caps how many runs
 * run at once on this instance (global backpressure). Each run acquires its project's lock first;
 * if the project is busy, the run is parked until that project's running turn releases it. On shutdown
 * the loop drains cleanly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWorker {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    private final AgentProperties props;
    private final AgentJobQueue queue;
    private final AgentRunRepository runRepository;
    private final AgentRuntime runtime;
    private final SandboxPolicy sandboxPolicy;

    private ProjectLeaseService leases;
    @Autowired
    void useLeases(ProjectLeaseService leases) { this.leases=leases; }

    private volatile boolean running = true;
    private ExecutorService pool;

    @PostConstruct
    void start() {
        // Before any worker thread exists: a misconfigured sandbox fails startup instead of running
        // a queued run's generated code on this host.
        sandboxPolicy.requireSafeExecution();
        int threads = Math.max(1, props.getWorkerConcurrency());
        pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "agent-worker");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < threads; i++) pool.submit(this::loop);
        log.info("Agent worker started with {} thread(s)", threads);
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) pool.shutdownNow();
    }

    private void loop() {
        while (running) {
            try {
                UUID runId = queue.dequeue(POLL_TIMEOUT);
                if (runId != null) process(runId);
            } catch (Exception e) {
                if (running) log.warn("Agent worker loop error: {}", e.getMessage());
            }
        }
    }

    /** One delivery from the queue; package-private for tests. */
    void process(UUID runId) {
        AgentRun run = runRepository.findById(runId).orElse(null);
        if (run == null || run.getStatus() != RunStatus.QUEUED) return; // already handled / gone

        UUID lockOwner=UUID.randomUUID();
        if (!queue.tryLock(run.getProjectId(), lockOwner)) {
            // Project busy: wait in its parked list (released when the running turn unlocks) instead
            // of re-polling. The worker thread is free for other projects immediately.
            queue.parkBehindLock(run.getProjectId(), runId);
            return;
        }
        RunFence fence=null;
        try {
            // A run can sit on the queue more than once (recovery requeue, park release). Another worker
            // may have executed it between our first read and taking the lock, so read it again.
            AgentRun current = runRepository.findById(runId).orElse(null);
            if (current == null || current.getStatus() != RunStatus.QUEUED) return;
            if(leases!=null) {
                fence=leases.claim(current.getProjectId(),runId,lockOwner);
                if(fence==null) return; // durable QUEUED intent is retried by the bounded recovery sweep
            }
            try(var owned=RunFence.bind(fence)) { runtime.run(current); }
        } finally {
            try { if(fence!=null) leases.release(fence); }
            finally { queue.unlockAndReleaseParked(run.getProjectId(), lockOwner); }

        }
    }
}
