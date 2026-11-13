package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;

import java.util.List;

/** Immutable unit grouping; it cannot grant ownership or dependency authority beyond the contract. */
public record ContractBatch(String id, List<String> unitIds, List<String> dependsOn, List<String> ownedPaths,
        List<DeterministicPlan.SharedInterface> interfaces, List<String> acceptanceIds,
        int inputCap, int outputCap, int estimatedOutputTokens) {
    public ContractBatch {
        BuildContract.identifier(id);
        unitIds = List.copyOf(unitIds);
        dependsOn = List.copyOf(dependsOn);
        ownedPaths = List.copyOf(ownedPaths);
        interfaces = List.copyOf(interfaces);
        acceptanceIds = List.copyOf(acceptanceIds);
        if (unitIds.isEmpty() || unitIds.size() > 12 || dependsOn.size() > 12 || ownedPaths.isEmpty()
                || ownedPaths.size() > 144 || interfaces.size() > 24 || acceptanceIds.size() > BehaviorCriteria.API_CAP + BehaviorCriteria.BROWSER_CAP
                || inputCap < 1 || inputCap > 32_000 || outputCap < 1 || outputCap > 6_000 || estimatedOutputTokens < 1) {
            throw new IllegalArgumentException("Invalid planned batch capacity");
        }
        unitIds.forEach(BuildContract::identifier);
        dependsOn.forEach(BuildContract::identifier);
        ownedPaths.forEach(BuildContract::canonical);
    }
}
