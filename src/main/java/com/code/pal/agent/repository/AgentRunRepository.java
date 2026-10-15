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

    /** Parallel reply accounting touches counters only; never re-saves status, liveness or save state. */
    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("update AgentRun r set r.promptTokens=r.promptTokens+:prompt,r.completionTokens=r.completionTokens+:completion where r.id=:id")
    int addReportedTokens(@Param("id") UUID id,@Param("prompt") int prompt,@Param("completion") int completion);

    java.util.Optional<AgentRun> findByIdAndProjectId(UUID id, UUID projectId);
    java.util.Optional<AgentRun> findFirstByProjectIdAndMessageIdOrderByCreatedAtAsc(UUID projectId, String messageId);
    java.util.Optional<AgentRun> findByProjectIdAndRequestMessageId(UUID projectId, String requestMessageId);

    @Modifying
    @Query("update AgentRun r set r.retrySourceRunId=:source where r.id=:id and r.projectId=:project "
            +"and r.retrySourceRunId is null and r.status=com.code.pal.agent.domain.RunStatus.QUEUED")
    int bindRetrySource(@Param("id") UUID id,@Param("project") UUID project,@Param("source") UUID source);

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("update AgentRun r set r.cancelRequestedBy = :actor, r.cancelRequestedAt = :now "
            + "where r.id = :id and r.projectId = :projectId and r.cancelRequestedAt is null "
            + "and r.status in (com.code.pal.agent.domain.RunStatus.QUEUED, com.code.pal.agent.domain.RunStatus.RUNNING)")
    int recordCancellation(@Param("id") UUID id, @Param("projectId") UUID projectId,
                           @Param("actor") Long actor, @Param("now") Instant now);

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AgentRun r set r.status=com.code.pal.agent.domain.RunStatus.COMMITTING,r.commitStartedAt=:now "
            + "where r.id=:id and r.projectId=:projectId and r.status=com.code.pal.agent.domain.RunStatus.RUNNING and r.cancelRequestedAt is null")
    int beginCommit(@Param("id") UUID id,@Param("projectId") UUID projectId,@Param("now") Instant now);

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AgentRun r set r.codeSavedAt=:now,r.previewStatus='NOT_REQUESTED' where r.id=:id and r.status=com.code.pal.agent.domain.RunStatus.COMMITTING")
    int recordCodeSaved(@Param("id") UUID id,@Param("now") Instant now);

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AgentRun r set r.previewStatus=:state where r.id=:id and r.codeSavedAt is not null")
    int recordPreviewStatus(@Param("id") UUID id,@Param("state") String state);

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AgentRun r set r.snapshotRef=:snapshot where r.id=:id and r.status=com.code.pal.agent.domain.RunStatus.COMMITTING")
    int recordSnapshot(@Param("id") UUID id,@Param("snapshot") String snapshot);

    @org.springframework.transaction.annotation.Transactional
    @Modifying
    @Query("update AgentRun r set r.error='Requeued after stale queue delay',r.updatedAt=:now where r.id=:id and r.status=com.code.pal.agent.domain.RunStatus.QUEUED")
    int markRequeued(@Param("id") UUID id,@Param("now") Instant now);

    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    @Modifying
    @Query("update AgentRun r set r.saveRecoveryRequired=true where r.id=:id and r.status=com.code.pal.agent.domain.RunStatus.COMMITTING and r.codeSavedAt is null")
    int flagSaveRecovery(@Param("id") UUID id);

    /** Most-recent runs for a project, newest first â€” capped so the timeline list can't grow unbounded. */
    List<AgentRun> findTop100ByProjectIdOrderByCreatedAtDesc(UUID projectId);

    java.util.Optional<AgentRun> findTopByProjectIdOrderByCreatedAtDesc(UUID projectId);

    /* ── Analytics aggregates ────────────────────────────────────────────────── */

    long countByProjectId(UUID projectId);

    long countByProjectIdAndStatus(UUID projectId, RunStatus status);

    @Query("select coalesce(sum(r.promptTokens + r.completionTokens), 0) from AgentRun r where r.projectId = :projectId")
    long sumTokensByProjectId(@Param("projectId") UUID projectId);

    /* ── Per-user usage limits ───────────────────────────────────────────────── */

    long countByUserIdAndStatusIn(Long userId, java.util.Collection<RunStatus> statuses);

    @Query("select coalesce(sum(r.promptTokens + r.completionTokens), 0) from AgentRun r "
            + "where r.userId = :userId and r.createdAt >= :since")
    long sumTokensByUserIdSince(@Param("userId") Long userId, @Param("since") Instant since);
}
