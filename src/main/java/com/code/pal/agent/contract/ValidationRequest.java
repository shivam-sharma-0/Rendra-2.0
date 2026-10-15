package com.code.pal.agent.contract;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Trusted validation inputs. Phase/batch do not change compilation identity for the same source. */
public record ValidationRequest(UUID runId, UUID projectId, UUID contractId, long planRevision,
        String batchId, int stagedRevision, String snapshotHash, String dependencyHash, String commandPlanHash,
        String criteriaHash, String capabilityRevision, String validatorRevision, Phase phase,
        Set<String> requiredChecks, Set<String> completedBatchIds) {
    public enum Phase { BATCH, FINAL }

    public ValidationRequest {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(contractId);
        Objects.requireNonNull(phase);
        requiredChecks = Set.copyOf(requiredChecks);
        completedBatchIds = Set.copyOf(completedBatchIds);
        if (planRevision < 1 || stagedRevision < 0 || phase == Phase.BATCH && (batchId == null || batchId.isBlank())
                || !requiredChecks.contains("BUILD")) {
            throw new IllegalArgumentException("Incomplete validation identity");
        }
        for (String value : List.of(snapshotHash, dependencyHash, commandPlanHash, criteriaHash, capabilityRevision, validatorRevision)) {
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("Invalid validation digest");
            }
        }
    }
}
