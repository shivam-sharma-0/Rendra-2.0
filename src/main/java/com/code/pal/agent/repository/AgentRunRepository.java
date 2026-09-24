package com.code.pal.agent.repository;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    /** Active runs for a project — concurrency guard (only one run should be live per project). */
    List<AgentRun> findByProjectIdAndStatusIn(UUID projectId, List<RunStatus> statuses);

    /** Runs stuck in a status past a cutoff — used to recover abandoned runs after a crash. */
    List<AgentRun> findByStatusInAndUpdatedAtBefore(List<RunStatus> statuses, Instant cutoff);
}
