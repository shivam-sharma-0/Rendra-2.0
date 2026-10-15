package com.code.pal.agent.contract;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

/** Partition source roles before the first v2 binding; never rewrites a reviewed contract or existing source. */
final class UnstoredUnitSplit {
    private UnstoredUnitSplit() {
    }

    static BuildContract split(BuildContract draft, BatchBudget.Limits limits, ObjectMapper mapper) {
        if (draft.schemaVersion() != 1) {
            throw new IllegalArgumentException("Only an unbound implementation draft can split unit ownership");
        }
        var estimator = new BatchBudget(mapper);
        var used = new HashSet<>(draft.units().stream().map(BuildContract.Unit::id).toList());
        var units = new ArrayList<BuildContract.Unit>();
        for (var unit : draft.units()) {
            if (ContractPlans.estimate(draft, List.of(unit.id()), limits, estimator).fits()) {
                units.add(unit);
                continue;
            }
            var source = unit.ownedPaths().stream()
                    .filter(path -> draft.assets().stream().noneMatch(asset -> asset.destination().equals(path))).toList();
            if (source.isEmpty()) {
                units.add(unit);
                continue;
            }
            String primary = source.stream().filter(path -> path.equals("src/App.tsx") || path.equals("src/App.jsx"))
                    .findFirst().orElseGet(() -> source.stream().filter(path -> draft.routes().stream()
                            .anyMatch(route -> route.componentPath().equals(path))).findFirst().orElse(source.getFirst()));
            var ids = new HashMap<String, String>();
            int suffix = 1;
            for (String path : source) {
                String id = unit.id();
                if (!path.equals(primary)) {
                    do {
                        id = unit.id().substring(0, Math.min(35, unit.id().length())) + "_part" + suffix++;
                    } while (used.contains(id));
                    used.add(id);
                }
                ids.put(path, id);
            }
            for (String path : source) {
                var owned = new ArrayList<>(List.of(path));
                if (path.equals(primary)) {
                    unit.ownedPaths().stream().filter(value -> !source.contains(value)).forEach(owned::add);
                }
                if (!ContractPlans.estimatePaths(draft, owned, limits, estimator).fits()) {
                    throw new IllegalArgumentException("An individual source file exceeds the fixed unit budget; propose smaller files");
                }
                var imports = new LinkedHashSet<>(unit.imports());
                source.stream().filter(value -> rank(draft, value) < rank(draft, path)).forEach(imports::add);
                imports.removeAll(owned);
                String objective = unit.objective().substring(0, Math.min(1_100, unit.objective().length()))
                        + " Implement only this file role. Use only declared imports. Foundational modules must be self-contained; "
                        + "page components compose available lower-level modules, never import sibling pages.";
                units.add(new BuildContract.Unit(ids.get(path), objective, owned, List.copyOf(imports),
                        unit.dependsOn(), unit.acceptanceIds()));
            }
        }
        if (units.equals(draft.units())) {
            return draft;
        }
        if (units.size() > 12) {
            throw new IllegalArgumentException("File partition needs more than 12 units; propose a smaller implementation layout");
        }
        var owners = new HashMap<String, String>();
        units.forEach(unit -> unit.ownedPaths().forEach(path -> owners.put(path, unit.id())));
        var rebound = units.stream().map(unit -> {
            var needs = new LinkedHashSet<>(unit.dependsOn());
            unit.imports().stream().map(owners::get).filter(owner -> owner != null && !owner.equals(unit.id())).forEach(needs::add);
            return new BuildContract.Unit(unit.id(), unit.objective(), unit.ownedPaths(), unit.imports(), List.copyOf(needs), unit.acceptanceIds());
        }).toList();
        var routes = draft.routes().stream().map(route -> new BuildContract.Route(route.path(), route.componentPath(), owners.get(route.componentPath()))).toList();
        var assets = draft.assets().stream().map(asset -> new BuildContract.Asset(asset.source(), asset.destination(), asset.fingerprint(),
                owners.get(asset.destination()))).toList();
        return new BuildContract(1, draft.id(), draft.stack(), draft.storage(), draft.auth(), draft.limits(), draft.theme(), routes,
                draft.types(), draft.models(), draft.api(), assets, draft.envNames(), rebound, draft.acceptance());
    }

    private static int rank(BuildContract contract, String path) {
        if (contract.routes().stream().anyMatch(route -> route.componentPath().equals(path))
                || path.equals("src/App.tsx") || path.equals("src/App.jsx")) {
            return 2;
        }
        return path.endsWith(".tsx") || path.endsWith(".jsx") || path.endsWith(".html") ? 1 : 0;
    }
}
