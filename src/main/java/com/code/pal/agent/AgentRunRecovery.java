package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.change.RestoreRecoveryService;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Recovers queued deliveries lost from Redis and stops runs abandoned by a crashed worker. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRecovery {
    private final ProjectLeaseService leases;
    private final RestoreRecoveryService restores;
    private final AgentProperties props;
    private final AgentRunRepository runs;
    private final AgentJobQueue queue;
    private final AgentEvents events;
    private final MessageRepository messages;
    private final RealtimeService realtime;
    private final SandboxPort sandbox;
    private final ChangeSetService changeSets;
    private final SiteBuildService sites;

    @Scheduled(fixedDelayString = "${app.agent.stale-sweep-interval-ms:30000}")
    public void sweep() {
        Instant now = Instant.now();
        long recoveryDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        restores.recoverPending(recoveryDeadline);
        if(leases!=null) for(var project:leases.pendingIoProjects()) {
            if(System.nanoTime()>recoveryDeadline) break;
            try { leases.recovering(project,()->{if(!leases.hasLiveLease(project)) sandbox.reconcilePendingOperations(project);}); }
            catch(RuntimeException pending) {log.warn("trusted_operation_recovery_pending project={} errorType={}",project,pending.getClass().getSimpleName());}
        }

        if(leases!=null) for(var id:leases.pendingPromotionRuns()) {
            if(System.nanoTime()>recoveryDeadline) break;
            runs.findById(id).ifPresent(this::failAbandoned);
        }

        // Parked runs whose project is no longer locked: the worker that held the lock died before
        // releasing them. Requeue them first so they are not counted as lost below.
        try {
            long released = queue.releaseOrphanedParked();
            if (released > 0) log.info("Released {} orphaned parked agent run(s)", released);
        } catch (RuntimeException redisUnavailable) {
            log.debug("Parked-run sweep skipped: {}", redisUnavailable.getMessage());
        }

        // QUEUED and untouched for a while: the Redis enqueue was probably lost (e.g. Redis was down
        // when the turn was posted). Requeuing is safe: a duplicate entry is ignored once the run starts.
        Instant queueCutoff = now.minusMillis(Math.max(1_000, props.getQueuedRecoveryMs()));
        for (AgentRun run : runs.findByStatusAndUpdatedAtBefore(RunStatus.QUEUED, queueCutoff)) recoverQueued(run);

        // RUNNING: reclaim only when the heartbeat has genuinely stopped (worker died), never while a
        // run is merely busy in a long LLM stream â€” a live worker keeps beating on a separate thread.
        Instant heartbeatCutoff = now.minusMillis(props.leaseMs());
        for (AgentRun run : runs.findByStatusAndHeartbeatAtBefore(RunStatus.RUNNING, heartbeatCutoff)) {
            // Secondary guard: if the project lock is still held, a worker is alive â€” leave it alone.
            if (leases==null && queue.isLocked(run.getProjectId())) continue;
            failAbandoned(run);
        }
        for (AgentRun run : runs.findByStatusAndHeartbeatAtBefore(RunStatus.COMMITTING, heartbeatCutoff)) {
            if (leases!=null || !queue.isLocked(run.getProjectId())) failAbandoned(run);
        }
    }

    private void recoverQueued(AgentRun run) {
        // Duplicate queue entries are harmless: after one worker moves it to RUNNING, later entries are ignored.
        if(runs.markRequeued(run.getId(),Instant.now())!=1) return;
        queue.enqueue(run.getId());
        log.info("Recovered queued agent run {}", run.getId());
    }

    private void failAbandoned(AgentRun run) {
        if(leases==null) { failAbandonedOwned(run);return; }
        try { leases.recovering(run.getProjectId(),()-> {
            if(leases.hasLiveLease(run.getProjectId()) || !sandbox.reconcilePendingOperations(run.getProjectId())) return;
            AgentRun current=runs.findById(run.getId()).orElse(null);
            if(current!=null && (current.getStatus()==RunStatus.RUNNING || current.getStatus()==RunStatus.COMMITTING || leases.hasPendingPromotion(current.getProjectId())))
                failAbandonedOwned(current);
        }); } catch(RuntimeException pending) { log.warn("run_recovery_pending runId={} errorType={}",run.getId(),pending.getClass().getSimpleName()); }
    }
    private void failAbandonedOwned(AgentRun run) {
        if(changeSets.requeueCheckpoint(run)) {
            // The durable outbox will dispatch even if the immediate queue delivery is unavailable.
            try {queue.enqueue(run.getId());}catch(RuntimeException unavailable){log.warn("checkpoint_delivery_deferred runId={}",run.getId());}
            return;
        }
        boolean restored;
        try {
            restored=changeSets.recoverPromotion(run);
            if(restored) sites.buildLater(run.getProjectId());
        } catch(com.code.pal.agent.change.PromotionRecoveryPendingException pending) {
            try{runs.flagSaveRecovery(run.getId());}catch(RuntimeException unavailable){log.warn("save_recovery_status_deferred runId={}",run.getId());}
            log.warn("promotion_recovery_pending runId={}",run.getId());return;
        }
        run.setStatus(RunStatus.FAILED);
        run.setError("Agent worker stopped before the run completed");
        if(run.getCodeSavedAt()!=null) { runs.recordPreviewStatus(run.getId(),"FAILED");run.setPreviewStatus("FAILED"); }
        runs.save(run);
        // Capture terminal failed output before cleanup; ambiguous evidence is retained for recovery.
        changeSets.cleanupAbandoned(run.getProjectId(), run.getId());
        String messageText = run.getCodeSavedAt()!=null ? "Your code changes were saved. Preview preparation was interrupted."
                : restored
                ? "The agent stopped before completing this response. Its workspace changes were restored."
                : "The agent stopped before completing this response. Please try again.";
        messages.findById(run.getMessageId()).ifPresent(message -> {
            message.setContent(messageText);
            message.setStatus(MessageStatus.FAILED);
            var saved = messages.save(message);
            realtime.sendToProject(run.getProjectId(), RealtimeMessage.of("MESSAGE_UPSERT", MessageResponse.from(saved)));
        });
        events.emit(run, AgentStepType.STATUS, restored
                ? "Run recovered after worker interruption; workspace restored."
                : "Run recovered after worker interruption.", null);
        log.warn("Marked stale running agent run {} as failed", run.getId());
    }
}
