package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;

/** Pure receipt identities/validation. One SQL row contains one entire validated batch. */
final class BatchReceipts {
    private BatchReceipts() {
    }

    static String key(ContractBatch batch) {
        return "~batch:" + ContractStore.hash(batch.id()).substring(0, 32);
    }

    static ContractBatch batch(List<UnitGenerator.Input> inputs) {
        if (inputs.isEmpty() || inputs.getFirst().contract().schemaVersion() != 2) {
            throw new IllegalArgumentException("A complete v2 batch is required");
        }
        var contract = inputs.getFirst().contract();
        var batch = contract.batches().stream().filter(value -> value.unitIds().contains(inputs.getFirst().unit().id())).findFirst().orElseThrow();
        BatchUnitGenerator.plannedInputs(contract, batch, inputs);
        return batch;
    }

    static String context(AgentRun run, List<UnitGenerator.Input> inputs, AgentProperties props, ObjectMapper mapper) {
        var batch = batch(inputs);
        var frozen = BatchUnitGenerator.plannedInputs(inputs.getFirst().contract(), batch, inputs);
        var versions = new TreeMap<String, String>();
        frozen.getFirst().snapshot().forEach((path, value) -> {
            if (!batch.ownedPaths().contains(path)) {
                versions.put(path, value.version());
            }
        });
        return ContractStore.hash("batch-receipt-v1/GENERATION/" + run.getRequestFingerprint() + "/"
                + ContractIdentity.hash(inputs.getFirst().contract(), mapper) + "/" + batch.id() + "/"
                + ContractScaffold.revision() + "/" + props.getMaxRepairAttempts() + "/" + props.getSandbox().isRequired()
                + "/" + props.getSandbox().isRuntimeCheck() + "/" + versions);
    }

    static List<UnitGenerator.Output> validate(BuildContract contract, ContractBatch batch, BatchUnitGenerator.Output receipt) {
        var originals = new TreeMap<String, UnitGenerator.FileView>();
        if (receipt == null || receipt.units() == null) {
            throw new IllegalStateException("Incomplete batch receipt");
        }
        for (var unit : receipt.units()) {
            if (unit == null || unit.files() == null) {
                throw new IllegalStateException("Incomplete batch unit receipt");
            }
            for (var delta : unit.files()) {
                if (delta == null || originals.putIfAbsent(delta.path(), new UnitGenerator.FileView(delta.expectedVersion(), null)) != null) {
                    throw new IllegalStateException("Duplicate or missing batch receipt file");
                }
            }
        }
        var inputs = batch.unitIds().stream().map(id -> contract.units().stream().filter(unit -> unit.id().equals(id)).findFirst().orElseThrow())
                .map(unit -> new UnitGenerator.Input(contract, unit, "", "", originals, "")).toList();
        return BatchUnitGenerator.validate(inputs, receipt);
    }

    static List<UnitGenerator.Output> replay(List<UnitGenerator.Output> latest, List<UnitGenerator.Output> first,
            AgentContext ctx, ProjectFiles files) {
        var baselines = new HashMap<String, String>();
        first.stream().flatMap(output -> output.files().stream()).forEach(delta -> baselines.put(delta.path(), delta.expectedVersion()));
        var result = new ArrayList<UnitGenerator.Output>();
        for (var output : latest) {
            var needed = new ArrayList<UnitGenerator.Delta>();
            for (var delta : output.files()) {
                String actual = files.version(ctx.projectId(), ctx.root(), delta.path());
                String after = delta.content() == null ? "absent" : "sha256:" + ContractStore.hash(delta.content());
                if (actual.equals(after)) {
                    continue;
                }
                if (!actual.equals(delta.expectedVersion()) && !actual.equals(baselines.get(delta.path()))) {
                    throw new IllegalStateException("Batch checkpoint target changed");
                }
                needed.add(new UnitGenerator.Delta(delta.path(), actual, delta.content()));
            }
            result.add(new UnitGenerator.Output(needed));
        }
        return List.copyOf(result);
    }
}
