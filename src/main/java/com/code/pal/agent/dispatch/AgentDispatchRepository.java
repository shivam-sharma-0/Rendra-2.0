package com.code.pal.agent.dispatch;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentDispatchRepository extends JpaRepository<AgentDispatch, UUID> {
    List<AgentDispatch> findTop100ByDispatchedAtIsNullOrderByCreatedAtAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AgentDispatch d where d.runId = :runId")
    Optional<AgentDispatch> lockByRunId(UUID runId);
}
