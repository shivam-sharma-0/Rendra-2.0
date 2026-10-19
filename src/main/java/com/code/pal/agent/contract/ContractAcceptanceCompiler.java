package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Completes bounded MERN acceptance ownership and removes unit claims on coordinator-owned files.
 * It adds no endpoint, permission, implementation or acceptance check beyond the existing CRUD fixture. */
final class ContractAcceptanceCompiler {
    private ContractAcceptanceCompiler() {
    }

    @SuppressWarnings("unchecked")
    static String completeCollections(String content, ObjectMapper mapper) {
        Map<String, Object> data = mapper.readValue(content, Map.class);
        if (!"MERN".equals(data.get("stack")))
            return content;
        boolean changed = removeExactDuplicates(data);
        changed |= resolveUnambiguousUnitIds(data);
        var criteria = mapper.convertValue(data.get("acceptance"), BehaviorCriteria.class);
        if (!criteria.crudRequired())
            return changed ? mapper.writeValueAsString(data) : content;
        var checks = new ArrayList<>(criteria.api());
        var ids = new HashSet<String>();
        checks.forEach(step -> ids.add(step.id()));
        criteria.browser().forEach(step -> ids.add(step.id()));
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        changed |= removeCoordinatorOwnedPaths(units);
        for (Object value : (List<?>) data.get("api")) {
            var api = mapper.convertValue(value, BuildContract.Api.class);
            if (!api.method().equals("GET") || api.path().contains(":") || !api.responseType().endsWith("[]")
                    || checks.stream().anyMatch(step -> step.method().equals("GET") && step.path().equals(api.path())
                            && step.status() >= 200 && step.status() < 300))
                continue;
            for (int i = 0; i < checks.size(); i++) {
                var read = checks.get(i);
                if (read.kind() != BehaviorCriteria.ApiKind.READ || !read.path().startsWith(api.path() + "/")
                        || read.assertPointer() == null || read.assertValue() == null)
                    continue;
                boolean created = checks.subList(0, i).stream().anyMatch(
                        step -> step.kind() == BehaviorCriteria.ApiKind.CREATE && step.path().equals(api.path())
                                && step.captureVariable() != null
                                && read.path().contains("${" + step.captureVariable() + "}"));
                if (!created || checks.size() >= 24)
                    continue;
                var owner = units.stream().filter(unit -> api.owner().equals(unit.get("id"))).findFirst().orElse(null);
                if (owner == null)
                    continue;
                var assigned = new ArrayList<>((List<String>) owner.get("acceptanceIds"));
                if (assigned.size() >= 48)
                    continue;
                String id = "collection_" + api.id();
                if (ids.contains(id))
                    continue;
                checks.add(i + 1,
                        new BehaviorCriteria.ApiStep(id, BehaviorCriteria.ApiKind.READ, "GET", api.path(), null, 200,
                                read.tokenVariable(), null, null, "/0" + read.assertPointer(), read.assertValue()));
                assigned.add(id);
                owner.put("acceptanceIds", assigned);
                ids.add(id);
                changed = true;
                break;
            }
        }
        changed |= normalizeWrappedListPointers(checks, data, mapper);
        // Responsibility is routing metadata, never a grant. Infer only from already-declared
        // exact file/API owners, retaining every required check and revalidating all ownership.
        var declared = ((List<?>) data.get("api")).stream()
                .map(value -> mapper.convertValue(value, BuildContract.Api.class)).toList();
        String frontend = null;
        for (var unit : units)
            if (((List<?>) unit.get("ownedPaths")).contains("client/src/App.tsx"))
                frontend = (String) unit.get("id");
        if (frontend == null)
            for (Object value : (List<?>) data.get("routes")) {
                var route = mapper.convertValue(value, BuildContract.Route.class);
                if (route.path().equals("/"))
                    frontend = route.owner();
            }
        for (var action : criteria.browser())
            changed |= assign(units, frontend, action.id());
        for (var check : checks) {
            String owner = declared.stream()
                    .filter(api -> api.method().equals(check.method())
                            && BuildContract.matchesApiPath(api.path(), check.path()))
                    .map(BuildContract.Api::owner).findFirst().orElse(null);
            if (owner == null && check.kind().name().startsWith("AUTH_")
                    || owner == null && check.kind() == BehaviorCriteria.ApiKind.HEALTH)
                owner = declared.isEmpty() ? null : declared.getFirst().owner();
            changed |= assign(units, owner, check.id());
        }
        if (!changed)
            return content;
        data.put("acceptance", new BehaviorCriteria(criteria.schema(), criteria.browser(), checks,
                criteria.authRequired(), criteria.crudRequired()));
        return mapper.writeValueAsString(data);
    }

    /** Only identical checks are expendable: unique actions have no reliable optional marker. */
    @SuppressWarnings("unchecked")
    private static boolean removeExactDuplicates(Map<String, Object> data) {
        Map<String, Object> acceptance = (Map<String, Object>) data.get("acceptance");
        boolean changed = false;
        for (String kind : List.of("browser", "api")) {
            List<Object> checks = (List<Object>) acceptance.get(kind);
            var distinct = new ArrayList<>(new LinkedHashSet<>(checks));
            if (distinct.size() != checks.size()) {
                acceptance.put(kind, distinct);
                changed = true;
            }
        }
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        var distinctUnits = new ArrayList<>(new LinkedHashSet<>(units));
        if (distinctUnits.size() != units.size()) {
            data.put("units", distinctUnits);
            changed = true;
        }
        return changed;
    }

    /** Renumber only when every reference to a duplicate unit can be resolved from an exact owned path. */
    @SuppressWarnings("unchecked")
    private static boolean resolveUnambiguousUnitIds(Map<String, Object> data) {
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        var counts = new LinkedHashMap<String, Integer>();
        var used = new HashSet<String>();
        for (var unit : units) {
            String id = (String) unit.get("id");
            counts.merge(id, 1, Integer::sum);
            used.add(id);
        }
        boolean changed = false;
        for (var group : counts.entrySet()) {
            String id = group.getKey();
            if (group.getValue() < 2 || id == null || id.length() > 44
                    || units.stream().anyMatch(unit -> ((List<?>) unit.get("dependsOn")).contains(id))
                    || data.get("batches") instanceof List<?> batches && batches.stream().anyMatch(batch ->
                            ((List<?>) ((Map<String, Object>) batch).get("unitIds")).contains(id)))
                continue;
            var candidates = units.stream().filter(unit -> id.equals(unit.get("id"))).toList();
            var names = new IdentityHashMap<Map<String, Object>, String>();
            var pathOwners = new HashMap<String, String>();
            boolean safe = true;
            for (int index = 0; index < candidates.size(); index++) {
                String proposed = id;
                if (index > 0) {
                    int suffix = index + 1;
                    proposed = id + "_" + suffix;
                    while (used.contains(proposed) && proposed.length() <= 48)
                        proposed = id + "_" + ++suffix;
                    if (proposed.length() > 48) { safe = false; break; }
                }
                names.put(candidates.get(index), proposed);
                for (String path : (List<String>) candidates.get(index).get("ownedPaths"))
                    if (pathOwners.putIfAbsent(path, proposed) != null) safe = false;
            }
            if (!safe) continue;
            var owners = new ArrayList<Map.Entry<Map<String, Object>, String>>();
            for (String kind : List.of("routes", "api", "assets")) {
                String pathKey = switch (kind) {
                    case "routes" -> "componentPath";
                    case "api" -> "routerPath";
                    default -> "destination";
                };
                for (var value : (List<Map<String, Object>>) data.get(kind)) {
                    if (!id.equals(value.get("owner"))) continue;
                    String owner = pathOwners.get(value.get(pathKey));
                    if (owner == null) safe = false;
                    else owners.add(Map.entry(value, owner));
                }
            }
            if (!safe) continue;
            names.forEach((unit, name) -> unit.put("id", name));
            owners.forEach(owner -> owner.getKey().put("owner", owner.getValue()));
            used.addAll(names.values());
            changed = true;
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static boolean removeCoordinatorOwnedPaths(List<Map<String, Object>> units) {
        boolean changed = false;
        for (Map<String, Object> unit : units) {
            List<String> paths = new ArrayList<>((List<String>) unit.get("ownedPaths"));
            if (paths.removeIf(path -> BuildContract.reserved(path, BuildContract.Stack.MERN))) {
                unit.put("ownedPaths", paths);
                changed = true;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static boolean normalizeWrappedListPointers(List<BehaviorCriteria.ApiStep> checks, Map<String, Object> data,
            ObjectMapper mapper) {
        List<BuildContract.Api> apis = ((List<?>) data.get("api")).stream()
                .map(value -> mapper.convertValue(value, BuildContract.Api.class)).toList();
        List<Map<String, Object>> types = (List<Map<String, Object>>) data.get("types");
        boolean changed = false;
        for (int index = 0; index < checks.size(); index++) {
            var check = checks.get(index);
            if (check.kind() != BehaviorCriteria.ApiKind.READ || check.assertPointer() == null
                    || !check.assertPointer().matches("/[0-9]+(?:/.*)?"))
                continue;
            var matching = apis.stream().filter(api -> api.method().equals(check.method())
                    && BuildContract.matchesApiPath(api.path(), check.path())).toList();
            if (matching.size() != 1 || matching.getFirst().responseType().endsWith("[]"))
                continue;
            String responseType = matching.getFirst().responseType();
            var declared = types.stream().filter(type -> responseType.equals(type.get("name"))).toList();
            if (declared.size() != 1)
                continue;
            List<Map<String, Object>> arrayFields = ((List<Map<String, Object>>) declared.getFirst().get("fields"))
                    .stream().filter(field -> field.get("kind") instanceof String kind && kind.endsWith("_ARRAY"))
                    .toList();
            if (arrayFields.size() != 1)
                continue;
            String field = (String) arrayFields.getFirst().get("name");
            checks.set(index, new BehaviorCriteria.ApiStep(check.id(), check.kind(), check.method(), check.path(),
                    check.body(), check.status(), check.tokenVariable(), check.captureVariable(), check.capturePointer(),
                    "/" + field + check.assertPointer(), check.assertValue()));
            changed = true;
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private static boolean assign(List<Map<String, Object>> units, String owner, String id) {
        if (owner == null || units.stream().anyMatch(unit -> ((List<?>) unit.get("acceptanceIds")).contains(id)))
            return false;
        var unit = units.stream().filter(value -> owner.equals(value.get("id"))).findFirst().orElse(null);
        if (unit == null)
            return false;
        var ids = new ArrayList<>((List<String>) unit.get("acceptanceIds"));
        if (ids.size() >= 48)
            return false;
        ids.add(id);
        unit.put("acceptanceIds", ids);
        return true;
    }
}
