package com.code.pal.agent.contract;

import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Completes implementation layout data. Approval, behavior, capability and budget fields are never model-written. */
final class SpecPlanCompletion {
    private static final Set<String> UNIT_FIELDS = Set.of("id", "objective", "ownedPaths", "files", "imports", "dependsOn");
    private record DraftUnit(String id, String objective, LinkedHashSet<String> paths, List<String> imports, List<String> dependencies) {
    }

    private SpecPlanCompletion() {
    }

    static BuildContract complete(ContractPlanner.SpecInput input, Map<?, ?> draft, ObjectMapper mapper) {
        var drafts = readUnits(draft);
        var spec = input.spec();
        if (spec.desired().auth() != ApprovedSpec.Auth.NONE) {
            throw new IllegalArgumentException("Approved account acceptance needs its platform adapter");
        }
        var pages = SpecPlanValues.pages(spec);
        var assets = completeFiles(spec, drafts, pages);
        var owners = owners(drafts);
        var assignments = acceptanceOwners(spec, drafts, pages, owners);
        var units = drafts.stream().map(unit -> {
            var needs = new LinkedHashSet<>(unit.dependencies());
            unit.imports().stream().map(owners::get).filter(owner -> owner != null && !owner.equals(unit.id())).forEach(needs::add);
            return new BuildContract.Unit(unit.id(), unit.objective(), List.copyOf(unit.paths()), unit.imports(),
                    List.copyOf(needs), List.copyOf(assignments.get(unit.id())));
        }).toList();
        var routes = pages.stream().map(page -> new BuildContract.Route(page.route(), page.path(), owners.get(page.path()))).toList();
        var acceptance = SpecPlanValues.acceptance(spec);
        Set<String> env = spec.desired().storage() == ApprovedSpec.Storage.SUPABASE
                ? Set.of("VITE_SUPABASE_URL", "VITE_SUPABASE_ANON_KEY") : Set.of();
        var base = new BuildContract(1, input.base().id(), BuildContract.Stack.valueOf(spec.desired().renderStack().name()),
                BuildDecisions.Storage.valueOf(spec.desired().storage().name()), BuildDecisions.Auth.valueOf(spec.desired().auth().name()),
                input.base().limits(), SpecPlanValues.theme(spec), routes, SpecPlanValues.types(spec), List.of(), List.of(), assets, env, units, acceptance);
        base = UnstoredUnitSplit.split(base, input.budget(), mapper);
        var ids = base.units().stream().map(BuildContract.Unit::id).toList();
        var allPaths = base.units().stream().flatMap(unit -> unit.ownedPaths().stream()).toList();
        var actionIds = acceptance.browser().stream().map(action -> action.id()).toList();
        var estimate = ContractPlans.estimate(base, ids, input.budget(), new BatchBudget(mapper));
        var batch = new ContractBatch("app", ids, List.of(), allPaths, List.of(), actionIds, input.budget().inputCap(),
                Math.min(input.budget().outputCap(), base.limits().outputTokens()), estimate.visibleTokens() + estimate.reasoningTokens());
        return ContractPlans.bind(base, spec, List.of(batch));
    }

    private static List<DraftUnit> readUnits(Map<?, ?> draft) {
        if (!draft.keySet().equals(Set.of("units")) || !(draft.get("units") instanceof List<?> proposals)
                || proposals.isEmpty() || proposals.size() > 12) {
            throw new IllegalArgumentException("Implementation units must contain 1 to 12 items");
        }
        var units = new ArrayList<DraftUnit>();
        for (int index = 0; index < proposals.size(); index++) {
            if (!(proposals.get(index) instanceof Map<?, ?> unit) || !UNIT_FIELDS.containsAll(unit.keySet())) {
                throw new IllegalArgumentException("Implementation unit has unknown fields or an invalid shape");
            }
            String defaultId = proposals.size() == 1 ? "app" : "unit" + (index + 1);
            if (unit.get("ownedPaths") != null && unit.get("files") != null) {
                throw new IllegalArgumentException("Use ownedPaths or files once per implementation unit");
            }
            var owned = paths(unit.get("ownedPaths") == null ? unit.get("files") : unit.get("ownedPaths"));
            if (owned.isEmpty()) {
                throw new IllegalArgumentException("Implementation unit needs ownedPaths");
            }
            units.add(new DraftUnit(text(unit, "id", defaultId), text(unit, "objective", "Implement the approved features in this unit's files"),
                    new LinkedHashSet<>(owned), paths(unit.get("imports")), strings(unit.get("dependsOn"))));
        }
        return List.copyOf(units);
    }

    private static List<BuildContract.Asset> completeFiles(ApprovedSpec spec, List<DraftUnit> units, List<SpecPlanValues.PageFile> pages) {
        // Required entrypoints/styles are fixed roles, not another model decision or correction call.
        for (var page : pages) {
            if (units.stream().noneMatch(unit -> unit.paths().contains(page.path()))) {
                units.getFirst().paths().add(page.path());
            }
        }
        String css = spec.desired().renderStack() == ApprovedSpec.RenderStack.STATIC ? "styles.css" : "src/index.css";
        if (units.stream().noneMatch(unit -> unit.paths().contains(css))) {
            units.getFirst().paths().add(css);
        }
        var assets = new ArrayList<BuildContract.Asset>();
        for (var asset : spec.spec().assets()) {
            BuildContract.canonical(asset.projectPath());
            String filename = asset.projectPath().substring(asset.projectPath().lastIndexOf('/') + 1);
            String prefix = spec.desired().renderStack() == ApprovedSpec.RenderStack.STATIC ? "assets/" : "public/assets/";
            String destination = prefix + asset.fingerprint().substring(7, 19) + "-" + filename;
            units.getFirst().paths().add(destination);
            assets.add(new BuildContract.Asset(asset.projectPath(), destination, asset.fingerprint(), units.getFirst().id()));
        }
        return List.copyOf(assets);
    }

    private static Map<String, String> owners(List<DraftUnit> units) {
        var owners = new HashMap<String, String>();
        for (var unit : units) {
            for (String path : unit.paths()) {
                if (owners.putIfAbsent(path, unit.id()) != null) {
                    throw new IllegalArgumentException("Overlapping implementation file ownership");
                }
            }
        }
        return owners;
    }

    private static Map<String, LinkedHashSet<String>> acceptanceOwners(ApprovedSpec spec, List<DraftUnit> units,
            List<SpecPlanValues.PageFile> pages, Map<String, String> owners) {
        var assignments = new HashMap<String, LinkedHashSet<String>>();
        units.forEach(unit -> assignments.put(unit.id(), new LinkedHashSet<>()));
        for (var scenario : spec.spec().acceptance()) {
            var feature = spec.spec().features().stream().filter(value -> value.id().equals(scenario.featureId())).findFirst().orElseThrow();
            if (feature.pageIds().isEmpty()) {
                throw new IllegalArgumentException("Approved feature needs an implemented page mapping");
            }
            for (var page : pages) {
                if (feature.pageIds().contains(page.pageId())) {
                    for (int step = 0; step < scenario.steps().size(); step++) {
                        assignments.get(owners.get(page.path())).add(scenario.id() + "_" + step);
                    }
                }
            }
        }
        return assignments;
    }

    private static String text(Map<?, ?> object, String key, String fallback) {
        Object value = object.get(key);
        if (value == null) {
            return fallback;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Implementation unit id or objective must be nonempty text");
        }
        return text;
    }

    private static List<String> paths(Object value) {
        return strings(value).stream().map(path -> {
            path = path.replace('\\', '/');
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            return BuildContract.canonical(path);
        }).distinct().toList();
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() > 20 || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("Implementation unit paths and dependencies must be bounded string arrays");
        }
        return list.stream().map(String.class::cast).toList();
    }
}
