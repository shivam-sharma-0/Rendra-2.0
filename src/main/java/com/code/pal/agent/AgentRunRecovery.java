package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.snapshot.WorkspaceSnapshotService;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.SandboxPort;
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
    private final AgentProperties props;
    private final AgentRunRepository runs;
    private final AgentJobQueue queue;
    private final AgentEvents events;
    private final MessageRepository messages;
    private final RealtimeService realtime;
    private final WorkspaceStorage workspace;
    private final WorkspaceSnapshotService snapshots;
    private final SandboxPort sandbox;
    private final ChangeSetService changeSets;

    @Scheduled(fixedDelayString = "${app.agent.stale-sweep-interval-ms:30000}")
    public void sweep() {
        Instant now = Instant.now();

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
            if (queue.isLocked(run.getProjectId())) continue;
            failAbandoned(run);
        }
    }

    private void recoverQueued(AgentRun run) {
        // Duplicate queue entries are harmless: after one worker moves it to RUNNING, later entries are ignored.
        queue.enqueue(run.getId());
        run.setError("Requeued after stale queue delay"); // marks updated_at so the sweep does not hot-loop
        runs.save(run);
        log.info("Recovered queued agent run {}", run.getId());
    }

    private void failAbandoned(AgentRun run) {
        boolean restored = false;
        if (props.isRollbackOnFailure() && run.getSnapshotRef() != null) {
            try {
                if (run.getSnapshotRef().startsWith("docker:")) {
                    sandbox.restoreTree(run.getProjectId(), ".codepal/history/" + run.getId() + "/original");
                } else {
                    snapshots.restore(workspace.ensure(run.getProjectId()), run.getSnapshotRef());
                }
                restored = true;
            } catch (Exception e) {
                log.error("Could not restore abandoned run {}: {}", run.getId(), e.getMessage());
            }
        }
        // The dead process took the in-memory change set with it; remove its staging area too.
        changeSets.cleanupAbandoned(run.getProjectId(), run.getId(),
                props.getSandbox().isRequired() ? null : workspace.projectDir(run.getProjectId()));
        run.setStatus(RunStatus.FAILED);
        run.setError("Agent worker stopped before the run completed");
        runs.save(run);
        String messageText = restored
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
