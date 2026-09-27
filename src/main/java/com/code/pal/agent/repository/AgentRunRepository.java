package com.code.pal.agent.repository;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** Active runs for a project â€” concurrency guard (only one run should be live per project). */
    List<AgentRun> findByProjectIdAndStatusIn(UUID projectId, List<RunStatus> statuses);

    /** Queued runs whose enqueue may have been lost from Redis (no worker picked them up in time). */
    List<AgentRun> findByStatusAndUpdatedAtBefore(RunStatus status, Instant cutoff);

    /** Running runs whose worker stopped renewing the heartbeat â€” i.e. the executing worker died. */
    List<AgentRun> findByStatusAndHeartbeatAtBefore(RunStatus status, Instant cutoff);

    /**
     * Column-scoped liveness beat: touches only heartbeat/updated timestamps so it never clobbers a
     * concurrent status/token write from the runner thread. Runs in its own short transaction.
     */
    @Modifying(clearAutomatically = true)
    @Query("update AgentRun r set r.heartbeatAt = :now, r.updatedAt = :now where r.id = :id")
    int touchHeartbeat(@Param("id") UUID id, @Param("now") Instant now);

    java.util.Optional<AgentRun> findByIdAndProjectId(UUID id, UUID projectId);

    /** Most-recent runs for a project, newest first â€” capped so the timeline list can't grow unbounded. */
    List<AgentRun> findTop100ByProjectIdOrderByCreatedAtDesc(UUID projectId);

    java.util.Optional<AgentRun> findTopByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /* ── Analytics aggregates ────────────────────────────────────────────────── */

    long countByProjectId(UUID projectId);

    long countByProjectIdAndStatus(UUID projectId, RunStatus status);

    @Query("select coalesce(sum(r.promptTokens + r.completionTokens), 0) from AgentRun r where r.projectId = :projectId")
    long sumTokensByProjectId(@Param("projectId") UUID projectId);
}
