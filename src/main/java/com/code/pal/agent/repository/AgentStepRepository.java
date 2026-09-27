package com.code.pal.agent.repository;

import com.code.pal.agent.domain.AgentStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentStepRepository extends JpaRepository<AgentStep, UUID> {

    /** A run's timeline in order â€” for replay on reload / reconnect. */
    List<AgentStep> findByRunIdOrderBySeqAsc(UUID runId);

    /** Next sequence number for a run (steps appended monotonically). */
    Optional<AgentStep> findTopByRunIdOrderBySeqDesc(UUID runId);

    List<AgentStep> findByRunIdAndSeqGreaterThanOrderBySeqAsc(UUID runId, int seq);
}
