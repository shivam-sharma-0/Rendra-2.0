package com.code.pal.agent.dispatch;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** Durable delivery intent, committed in the transaction that accepted the user request. */
@Entity
@Table(name = "agent_dispatch_outbox")
@Getter @Setter @NoArgsConstructor
public class AgentDispatch {
    @Id @Column(name = "run_id") private UUID runId;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "dispatched_at") private Instant dispatchedAt;

    public AgentDispatch(UUID runId) {
        this.runId = runId;
        this.createdAt = Instant.now();
    }
}
