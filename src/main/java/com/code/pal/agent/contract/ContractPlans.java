package com.code.pal.agent.contract;

import com.code.pal.agent.starter.Starter;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Pure versioned planning and repartitioning. Persisted/reviewed contracts are never overwritten here. */
public final class ContractPlans {
    private ContractPlans() {}

    public static BuildContract fromLayout(UUID id, ApprovedSpec spec, DeterministicPlan.Layout layout,
            BuildContract.Limits limits) {
        if (!layout.specId().equals(spec.specId()) || layout.specRevision() != spec.specRevision()
                || !layout.specHash().equals(spec.specHash())) {
            throw new IllegalArgumentException("Layout belongs to a different approved spec");
        }
        var batch = layout.batch();
        var base = new BuildContract(1, id, BuildContract.Stack.valueOf(layout.stack().name()),
                BuildDecisions.Storage.valueOf(layout.storage().name()), BuildDecisions.Auth.valueOf(layout.auth().name()),
                limits, layout.theme(), layout.routes(), layout.types(), List.of(), List.of(), List.of(), Set.of(),
                layout.units(), layout.acceptance());
        return bind(base, spec, List.of(new ContractBatch(batch.id(), batch.unitIds(), batch.dependsOn(), batch.ownedPaths(),
                batch.interfaces(), batch.acceptanceIds(), batch.inputCap(), Math.min(batch.outputCap(), limits.outputTokens()),
                batch.estimatedOutputTokens())));
    }

    public static BuildContract bind(BuildContract base, ApprovedSpec spec, List<ContractBatch> batches) {
        if (!base.stack().name().equals(spec.desired().renderStack().name())
                || !base.storage().name().equals(spec.desired().storage().name())
                || !base.auth().name().equals(spec.desired().auth().name())) {
            throw new IllegalArgumentException("Plan differs from approved capabilities");
        }
        return copy(base, base.id(), base.units(), batches, spec.specId(), spec.specRevision(), spec.specHash(),
                1, null, base.schemaVersion() == 2 ? base.templateVersion() : Starter.TEMPLATE_VERSION);
    }

    static void validateBatches(List<BuildContract.Unit> units, List<ContractBatch> batches, BuildContract.Limits limits) {
        if (batches.isEmpty() || batches.size() > 12 || batches.size() > limits.modelCalls()) {
            throw new IllegalArgumentException("Invalid planned batch count");
        }
        Map<String, BuildContract.Unit> byUnit = units.stream().collect(Collectors.toMap(BuildContract.Unit::id, unit -> unit));
        var owner = new HashMap<String, String>();
        var batchIds = new HashSet<String>();
        for (var batch : batches) {
            if (!batchIds.add(batch.id()) || batch.outputCap() > limits.outputTokens()) {
                throw new IllegalArgumentException("Duplicate batch or raised output cap");
            }
            var paths = new HashSet<String>();
            var acceptance = new HashSet<String>();
            for (String unitId : batch.unitIds()) {
                var unit = byUnit.get(unitId);
                if (unit == null || owner.putIfAbsent(unitId, batch.id()) != null) {
                    throw new IllegalArgumentException("Batch unit is missing or duplicated");
                }
                paths.addAll(unit.ownedPaths());
                acceptance.addAll(unit.acceptanceIds());
            }
            if (!paths.equals(new HashSet<>(batch.ownedPaths())) || paths.size() != batch.ownedPaths().size()
                    || !acceptance.equals(new HashSet<>(batch.acceptanceIds()))
                    || acceptance.size() != batch.acceptanceIds().size()) {
                throw new IllegalArgumentException("Batch ownership or acceptance differs from its units");
            }
            var interfacePaths = new HashSet<String>();
            for (var shared : batch.interfaces()) {
                BuildContract.canonical(shared.path());
                if (!interfacePaths.add(shared.path()) || shared.hash() == null
                        || !shared.hash().matches("sha256:[a-f0-9]{64}") || paths.contains(shared.path())) {
                    throw new IllegalArgumentException("Invalid shared interface identity");
                }
                shared.exportedSymbols().forEach(BuildContract::identifier);
            }
        }
        if (!owner.keySet().equals(byUnit.keySet())) {
            throw new IllegalArgumentException("Planned batch omitted a unit");
        }
        for (var batch : batches) {
            var dependencies = new HashSet<String>();
            for (String unitId : batch.unitIds()) {
                byUnit.get(unitId).dependsOn().stream().map(owner::get).filter(id -> !id.equals(batch.id()))
                        .forEach(dependencies::add);
            }
            if (!dependencies.equals(new HashSet<>(batch.dependsOn())) || dependencies.size() != batch.dependsOn().size()) {
                throw new IllegalArgumentException("Batch dependencies differ from unit dependencies");
            }
        }
        ordered(batches);
    }

    public static List<ContractBatch> ordered(List<ContractBatch> batches) {
        var pending = new ArrayList<>(batches);
        var done = new HashSet<String>();
        var result = new ArrayList<ContractBatch>();
        while (!pending.isEmpty()) {
            var ready = pending.stream().filter(batch -> done.containsAll(batch.dependsOn()))
                    .sorted(Comparator.comparing(ContractBatch::id)).toList();
            if (ready.isEmpty()) {
                throw new IllegalArgumentException("Cyclic or missing batch dependency");
            }
            result.addAll(ready);
            ready.forEach(batch -> done.add(batch.id()));
            pending.removeAll(ready);
        }
        return List.copyOf(result);
    }

    public static BuildContract splitOversized(BuildContract original, BatchBudget.Limits limits, ObjectMapper mapper) {
        if (original.schemaVersion() != 2) {
            throw new IllegalArgumentException("Only a versioned plan can be repartitioned");
        }
        var estimator = new BatchBudget(mapper);
        limits = effectiveLimits(original, limits);
        var admitted = limits;
        if (original.batches().stream().allMatch(batch -> estimate(original, batch.unitIds(), admitted, estimator).fits())) {
            return original;
        }
        var groups = new ArrayList<List<BuildContract.Unit>>();
        var current = new ArrayList<BuildContract.Unit>();
        for (var unit : original.waves().stream().flatMap(List::stream).toList()) {
            var proposed = new ArrayList<>(current);
            proposed.add(unit);
            if (!estimate(original, proposed.stream().map(BuildContract.Unit::id).toList(), limits, estimator).fits()) {
                if (current.isEmpty()) {
                    throw new IllegalArgumentException("An oversized unit needs a replacement planner proposal");
                }
                groups.add(List.copyOf(current));
                current.clear();
                if (!estimate(original, List.of(unit.id()), limits, estimator).fits()) {
                    throw new IllegalArgumentException("An oversized unit needs a replacement planner proposal");
                }
            }
            current.add(unit);
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        var owners = new HashMap<String, String>();
        for (int index = 0; index < groups.size(); index++) {
            String batchId = "batch" + (index + 1);
            groups.get(index).forEach(unit -> owners.put(unit.id(), batchId));
        }
        var batches = new ArrayList<ContractBatch>();
        for (var group : groups) {
            List<String> ids = group.stream().map(BuildContract.Unit::id).toList();
            String id = owners.get(ids.getFirst());
            var dependencies = group.stream().flatMap(unit -> unit.dependsOn().stream()).map(owners::get)
                    .filter(dependency -> !dependency.equals(id)).distinct().sorted().toList();
            var paths = group.stream().flatMap(unit -> unit.ownedPaths().stream()).toList();
            var acceptance = group.stream().flatMap(unit -> unit.acceptanceIds().stream()).distinct().toList();
            Set<String> imports = group.stream().flatMap(unit -> unit.imports().stream()).collect(Collectors.toSet());
            var interfaces = original.batches().stream().flatMap(batch -> batch.interfaces().stream())
                    .filter(shared -> imports.contains(shared.path())).distinct().toList();
            var estimate = estimate(original, ids, limits, estimator);
            batches.add(new ContractBatch(id, ids, dependencies, paths, interfaces, acceptance, limits.inputCap(),
                    limits.outputCap(), estimate.visibleTokens() + estimate.reasoningTokens()));
        }
        String identity = original.id() + ":split:" + (original.planRevision() + 1)
                + groups.stream().map(group -> group.stream().map(BuildContract.Unit::id).toList()).toList();
        UUID replacementId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        String oldRoot = original.clientRoot();
        String newRoot = (original.stack() == BuildContract.Stack.STATIC ? "rendra/" : "src/rendra/") + replacementId + "/";
        var units = original.units().stream().map(unit -> new BuildContract.Unit(unit.id(), unit.objective(), unit.ownedPaths(),
                unit.imports().stream().map(path -> path.replace(oldRoot, newRoot)).toList(), unit.dependsOn(), unit.acceptanceIds())).toList();
        var rebased = batches.stream().map(batch -> new ContractBatch(batch.id(), batch.unitIds(), batch.dependsOn(), batch.ownedPaths(),
                batch.interfaces().stream().map(shared -> new DeterministicPlan.SharedInterface(shared.path().replace(oldRoot, newRoot),
                        shared.hash(), shared.exportedSymbols(), shared.types())).toList(), batch.acceptanceIds(), batch.inputCap(),
                batch.outputCap(), batch.estimatedOutputTokens())).toList();
        return copy(original, replacementId, units, rebased, original.specId(), original.specRevision(), original.specHash(),
                original.planRevision() + 1, original.id(), original.templateVersion());
    }

    private static BatchBudget.Limits effectiveLimits(BuildContract contract, BatchBudget.Limits limits) {
        int input = Math.min(limits.inputCap(), contract.batches().stream().mapToInt(ContractBatch::inputCap).min().orElseThrow());
        int output = Math.min(limits.outputCap(), contract.batches().stream().mapToInt(ContractBatch::outputCap).min().orElseThrow());
        return new BatchBudget.Limits(input, Math.min(output, contract.limits().outputTokens()), limits.modelContextTokens(),
                limits.providerOverheadTokens(), limits.verifiedOutputPolicy());
    }

    /** Only trusted scaffold files define shared ABI identities; model-proposed hashes grant no authority. */
    public static BuildContract sealInterfaces(BuildContract contract, Map<String, String> scaffold) {
        var batches = contract.batches().stream().map(batch -> {
            var imports = contract.units().stream().filter(unit -> batch.unitIds().contains(unit.id()))
                    .flatMap(unit -> unit.imports().stream()).filter(path -> !batch.ownedPaths().contains(path)).distinct().sorted().toList();
            var interfaces = new ArrayList<DeterministicPlan.SharedInterface>();
            for (String path : imports) {
                String source = scaffold.get(path);
                if (source != null && path.startsWith(contract.clientRoot())) {
                    interfaces.add(new DeterministicPlan.SharedInterface(path, "sha256:" + ContractStore.hash(source),
                            contract.types().stream().map(BuildContract.DataType::name).toList(), contract.types()));
                } else if (!contract.ownership().containsKey(path)) {
                    throw new IllegalArgumentException("A shared import has no trusted scaffold definition");
                }
            }
            return new ContractBatch(batch.id(), batch.unitIds(), batch.dependsOn(), batch.ownedPaths(), interfaces,
                    batch.acceptanceIds(), batch.inputCap(), batch.outputCap(), batch.estimatedOutputTokens());
        }).toList();
        return copy(contract, contract.id(), contract.units(), batches, contract.specId(), contract.specRevision(),
                contract.specHash(), contract.planRevision(), contract.replacesContractId(), contract.templateVersion());
    }

    static BatchBudget.Estimate estimate(BuildContract contract, List<String> ids, BatchBudget.Limits limits,
            BatchBudget estimator) {
        var files = contract.units().stream().filter(unit -> ids.contains(unit.id())).flatMap(unit -> unit.ownedPaths().stream())
                .filter(path -> contract.assets().stream().noneMatch(asset -> asset.destination().equals(path)))
                .map(path -> new BatchBudget.Source(path, "absent", projectedCharacters(path), projectedCharacters(path))).toList();
        return estimator.estimate(files, "{}", limits);
    }

    private static int projectedCharacters(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/app.tsx") || lower.endsWith("/app.jsx")) {
            return 4_000;
        }
        if (lower.endsWith(".html")) {
            return 3_000;
        }
        if (lower.endsWith(".css") || lower.endsWith("local-state.ts")) {
            return 1_000;
        }
        return 2_000;
    }

    private static BuildContract copy(BuildContract base, UUID id, List<BuildContract.Unit> units, List<ContractBatch> batches,
            UUID specId, long specRevision, String specHash, long revision, UUID replaces, String template) {
        return new BuildContract(2, id, base.stack(), base.storage(), base.auth(), base.limits(), base.theme(), base.routes(),
                base.types(), base.models(), base.api(), base.assets(), base.envNames(), units, base.acceptance(),
                specId, specRevision, specHash, revision, replaces, template, batches);
    }
}
