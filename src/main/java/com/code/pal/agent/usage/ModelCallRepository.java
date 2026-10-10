package com.code.pal.agent.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface ModelCallRepository extends JpaRepository<ModelCall, UUID> {

    /** Tokens of the user's calls since {@code since} that no run total includes (intent, vision, requirements). */
    @Query("select coalesce(sum(c.promptTokens + c.completionTokens), 0) from ModelCall c "
            + "where c.userId = :userId and c.createdAt >= :since and c.countedInRun = false")
    long sumUncountedTokensByUserIdSince(@Param("userId") Long userId, @Param("since") Instant since);
}
