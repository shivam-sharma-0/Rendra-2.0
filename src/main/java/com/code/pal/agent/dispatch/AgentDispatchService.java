package com.code.pal.agent.dispatch;

import com.code.pal.agent.AgentEvents;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** At-least-once queue delivery; project/run claims prevent duplicate expensive execution. */
@Service @RequiredArgsConstructor @Slf4j
public class AgentDispatchService {
    private final AgentDispatchRepository outbox;
    private final AgentRunRepository runs;
    private final AgentJobQueue queue;
    private final AgentEvents events;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    public void setProjectionStore(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(UUID runId) {
        var delivery = outbox.lockByRunId(runId).orElse(null);
        if (delivery == null || delivery.getDispatchedAt() != null) return;
        var run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("Outbox run missing"));
        if (jdbc != null && run.getMode() == com.code.pal.project.domain.ProjectType.BUILD
                && Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from requirement_sessions s join requirement_projections p on p.id=s.projection_id where s.project_id=? and p.state='PENDING')",
                Boolean.class, run.getProjectId()))) return;
        if (run.getStatus() == RunStatus.QUEUED) {
            queue.enqueue(runId); // Failure rolls back; polling can retry after restart.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try {
                        events.emit(run, AgentStepType.STATUS, "Queued", Map.of("status", RunStatus.QUEUED.name()));
                    } catch (RuntimeException eventFailure) {
                        log.warn("agent_queue_event_failed runId={}", runId);
                    }
                }
            });
        }
        delivery.setDispatchedAt(Instant.now());
        outbox.save(delivery);
    }
}
