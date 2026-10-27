package com.code.pal.agent.contract;

import com.code.pal.agent.validation.AccountAcceptance;

import com.code.pal.agent.validation.BehaviorCriteria;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Completes bounded MERN acceptance ownership and removes unit claims on coordinator-owned files.
 * It adds no permission, implementation or acceptance check beyond the existing CRUD fixture, and no endpoint except
 * the record GET that the contract's own 2xx read checks already request. */
@lombok.extern.slf4j.Slf4j
final class ContractAcceptanceCompiler {
    private static final Set<String> REQUIRED_UI_IDS = Set.of("ui_add", "ui_created", "ui_edit", "ui_edited",
            "ui_complete", "ui_completed", "ui_delete", "ui_deleted", "ui_reload", "ui_persisted");
    private static final Pattern PREFERENCE = Pattern.compile(
            "(?i)\\b(theme|compact|density|sort|preferences?|prefs?|appearance|dark|light)\\b");
    private static final Pattern CORE_BEHAVIOR = Pattern.compile(
            "(?i)\\b(task|todo|item|title|edit|delete|complete|search|filter|account|auth|password|email)\\b");
    private static final Pattern CONTROL_ATTRIBUTE = Pattern.compile(
            "\\[(?:name|data-(?:pref|preference|action|testid))\\s*=\\s*['\"]([^'\"]+)['\"]\\]");
    private static final Pattern HREF = Pattern.compile("\\[href\\s*=\\s*['\"]([^'\"]*)['\"]\\]");
    private ContractAcceptanceCompiler() {
    }

    @SuppressWarnings("unchecked")
    static String completeCollections(String content, ObjectMapper mapper) {
        return completeCollections(content, mapper, null);
    }

    @SuppressWarnings("unchecked")
    static String completeCollections(String content, ObjectMapper mapper, String request, String approved) {
        String behavior = approved;
        try {
            var scope = new LinkedHashMap<String, Object>(mapper.readValue(approved, Map.class));
            removeDesignPalette(scope);
            if (scope.get("requestedScope") instanceof Map<?, ?> requested) {
                var requestedBehavior = new LinkedHashMap<>((Map<String, Object>) requested);
                removeDesignPalette(requestedBehavior);
                scope.put("requestedScope", requestedBehavior);
            }
            behavior = mapper.writeValueAsString(scope);
        } catch (RuntimeException legacyScope) { /* Retain the full text when structured scope is unavailable. */ }
        return compileCollections(content, mapper, request + "\n" + behavior, approved);
    }

    private static void removeDesignPalette(Map<String, Object> scope) {
        if (scope.get("theme") instanceof Map<?, ?> palette
                && Set.of("background", "foreground", "accent", "muted", "radius", "spacing", "font")
                        .containsAll(palette.keySet()))
            scope.remove("theme");
    }

    @SuppressWarnings("unchecked")
    static String completeCollections(String content, ObjectMapper mapper, String requiredScope) {
        return compileCollections(content, mapper, requiredScope, null);
    }

    @SuppressWarnings("unchecked")
    private static String compileCollections(String content, ObjectMapper mapper, String requiredScope, String approved) {
        Map<String, Object> data = mapper.readValue(content, Map.class);
        boolean changed = fillRouteValues(data);
        changed |= ContractDependencies.normalize(data, approved, mapper);
        if (!"MERN".equals(data.get("stack")))
            return changed ? mapper.writeValueAsString(data) : content;
        changed |= removeExactDuplicates(data);
        changed |= resolveUnambiguousUnitIds(data);
        changed |= removeCoordinatorOwnedPaths((List<Map<String, Object>>) data.get("units"));
        changed |= mergeUnitsSharingFiles(data);
        // Before the collection insertion and responsibility loop below: both read api.owner.
        changed |= synthesizeRouterUnits(data);
        List<Map<String, Object>> units = (List<Map<String, Object>>) data.get("units");
        changed |= alignOwnersToFiles(data, true);
        changed |= alignOwnersToFiles(data, false);
        changed |= trimOptionalBrowserChecks(data, requiredScope);
        changed |= bindAccountApiGuards(data);
        changed |= AccountAcceptance.normalize(data, mapper);
        var criteria = mapper.convertValue(data.get("acceptance"), BehaviorCriteria.class);
        if (!criteria.crudRequired())
            return changed ? mapper.writeValueAsString(data) : content;
        changed |= declareRecordReads(data, criteria.api(), units, mapper);
        var checks = new ArrayList<>(criteria.api());
        var ids = new HashSet<String>();
        checks.forEach(step -> ids.add(step.id()));
        criteria.browser().forEach(step -> ids.add(step.id()));
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
                    || owner == null && check.kind() == BehaviorCriteria.ApiKind.HEALTH) {
                // The first api whose owner is a unit; a non-unit first owner is a defect the validator reports.
                owner = declared.stream().map(BuildContract.Api::owner)
                        .filter(id -> units.stream().anyMatch(unit -> id.equals(unit.get("id")))).findFirst().orElse(null);
                if (owner != null && !owner.equals(declared.getFirst().owner()) && assign(units, owner, check.id())) {
                    log.info("planner_platform_check_owner_fallback id={} owner={} skipped={}",
                            check.id().matches("[A-Za-z0-9_-]{1,64}") ? check.id() : "<unsafe>",
                            owner.matches("[A-Za-z0-9_-]{1,64}") ? owner : "<unsafe>", declared.getFirst().owner());
                    changed = true;
                    continue;
                }
            }
            changed |= assign(units, owner, check.id());
        }
        if (!changed)
            return content;
        data.put("acceptance", new BehaviorCriteria(criteria.schema(), criteria.browser(), checks,
                criteria.authRequired(), criteria.crudRequired()));
        return mapper.writeValueAsString(data);
    }

    @SuppressWarnings("unchecked")
    private static boolean fillRouteValues(Map<String, Object> data) {
        if (!(data.get("acceptance") instanceof Map<?, ?> acceptance)
                || !(acceptance.get("browser") instanceof List<?> browser)
                || !(data.get("routes") instanceof List<?> routes)) return false;
        var declared = routes.stream().filter(value -> value instanceof Map<?, ?>)
                .map(value -> ((Map<?, ?>) value).get("path")).filter(value -> value instanceof String)
                .map(String.class::cast).filter(path -> path.matches("/[A-Za-z0-9_/:.-]{0,120}")
                        && !path.contains("..") && !path.contains("//")).toList();
        boolean changed = false;
        for (int index = 0; index < browser.size(); index++) {
            if (!(browser.get(index) instanceof Map<?, ?> raw) || !"ASSERT_ROUTE".equals(raw.get("kind"))
                    || raw.get("value") != null && (!(raw.get("value") instanceof String text) || !text.isBlank())
                    || !(raw.get("id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,64}")) continue;
            var candidates = new LinkedHashSet<String>();
            if (raw.get("selector") instanceof String target && (target.startsWith("/") || target.startsWith("#/"))
                    && !addRouteCandidate(candidates, target, declared)) continue;
            boolean unknownHref = false;
            if (index > 0 && browser.get(index - 1) instanceof Map<?, ?> previous
                    && "CLICK".equals(previous.get("kind")) && previous.get("selector") instanceof String selector) {
                var hrefs = HREF.matcher(selector);
                while (hrefs.find()) unknownHref |= !addRouteCandidate(candidates, hrefs.group(1), declared);
            }
            if (unknownHref || candidates.size() != 1) continue;
            String route = candidates.iterator().next();
            ((Map<String, Object>) raw).put("value", route);
            log.info("planner_route_assertion_filled id={} step={} value={}", id, index + 1, route);
            changed = true;
        }
        return changed;
    }

    private static boolean addRouteCandidate(Set<String> candidates, Object value, List<String> declared) {
        if (!(value instanceof String text)) return false;
        String path = text.startsWith("#/") ? text.substring(1) : text;
        if (path.matches("/[A-Za-z0-9_/.-]{0,120}") && !path.contains("..") && !path.contains("//")
                && declared.stream().anyMatch(shape -> BuildContract.matchesApiPath(shape, path))) {
            candidates.add(path);
            return true;
        }
        return false;
    }

    /** ContractScaffold mounts every ACCOUNTS router behind requireAccount, so a false flag only misdescribes it.
     * Coordinator /api/auth paths and non-boolean flags stay for the validator. */
    private static boolean bindAccountApiGuards(Map<String, Object> data) {
        if (!"ACCOUNTS".equals(data.get("auth")) || !(data.get("api") instanceof List<?> api)) return false;
        boolean changed = false;
        for (Object value : api)
            if (value instanceof Map<?, ?> raw && Boolean.FALSE.equals(raw.get("authenticated"))
                    && raw.get("path") instanceof String path && !path.equals("/api/auth") && !path.startsWith("/api/auth/")) {
                @SuppressWarnings("unchecked")
                var endpoint = (Map<String, Object>) raw;
                endpoint.put("authenticated", true);
                changed = true;
                log.info("planner_api_auth_guard_bound id={}", endpoint.get("id") instanceof String id
                        && id.matches("[A-Za-z0-9_-]{1,64}") ? id : "?");
            }
        return changed;
    }

    /** Fail closed: unknown actions and required scope stay intact for the existing model correction. */
    @SuppressWarnings("unchecked")
    private static boolean trimOptionalBrowserChecks(Map<String, Object> data, String requiredScope) {
        var acceptance = (Map<String, Object>) data.get("acceptance");
        var browser = (List<Map<String, Object>>) acceptance.get("browser");
        if (browser.size() <= 24) return false;
        boolean optionalPreferences = requiredScope != null
                && requiredScope.chars().allMatch(value -> value < 128)
                && !PREFERENCE.matcher(requiredScope.replace('_', ' ')
                        .replaceAll("([a-z])([A-Z])", "$1 $2")).find();
        var preferenceRoutes = new HashSet<String>();
        for (var route : (List<Map<String, Object>>) data.get("routes"))
            if (Set.of("/settings", "/preferences").contains(route.get("path")))
                preferenceRoutes.add((String) route.get("path"));
        var kept = new ArrayList<Map<String, Object>>();
        var dropped = new ArrayList<String>();
        var ids = new HashSet<String>();
        var seenAssertions = new HashSet<List<Object>>();
        boolean onPreferences = false;
        for (var action : browser) {
            String kind = Objects.toString(action.get("kind"), "");
            String selector = Objects.toString(action.get("selector"), "");
            String id = Objects.toString(action.get("id"), "");
            boolean assertion = Set.of("ASSERT_TEXT", "ASSERT_COUNT", "ASSERT_EXISTS", "ASSERT_ROUTE").contains(kind);
            // An invalid identity cannot be repaired into a valid contract by removing its action.
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) return false;
            var signature = Arrays.asList(action.get("kind"), action.get("selector"), action.get("value"));
            boolean duplicate = assertion && seenAssertions.contains(signature) && !REQUIRED_UI_IDS.contains(id);
            boolean preference = optionalPreferences && onPreferences && !REQUIRED_UI_IDS.contains(id)
                    && Set.of("SELECT", "CLICK", "ASSERT_TEXT", "ASSERT_COUNT", "ASSERT_EXISTS").contains(kind)
                    && CONTROL_ATTRIBUTE.matcher(selector).results()
                            .anyMatch(match -> PREFERENCE.matcher(match.group(1)).find())
                    && !CORE_BEHAVIOR.matcher(selector).find()
                    && !CORE_BEHAVIOR.matcher(id.replace('_', ' ')).find();
            if (duplicate || preference) {
                dropped.add(id);
            } else {
                kept.add(action);
            }
            // Even a removed mutation is a barrier: never dedupe evidence across a state change.
            if (assertion) seenAssertions.add(signature); else seenAssertions.clear();
            if (kind.equals("ASSERT_ROUTE"))
                onPreferences = preferenceRoutes.contains(action.get("value"));
            else if (!assertion && !preference)
                onPreferences = false; // Unknown click/fill/reload may navigate or start a required flow.
        }
        if (kept.size() > 24 || dropped.isEmpty()) return false;
        acceptance.put("browser", kept);
        var removedIds = new HashSet<>(dropped);
        kept.forEach(action -> removedIds.remove(action.get("id")));
        for (var unit : (List<Map<String, Object>>) data.get("units")) {
            var assigned = new ArrayList<>((List<String>) unit.get("acceptanceIds"));
            assigned.removeIf(removedIds::contains);
            unit.put("acceptanceIds", assigned);
        }
        log.info("planner_optional_browser_checks_dropped ids={} before={} after={}", dropped, browser.size(), kept.size());
        return true;
    }

    /** Exact repeats are safe to remove even when approved scope is unavailable. */
    @SuppressWarnings("unchecked")
    private static boolean removeExactDuplicates(Map<String, Object> data) {
        Map<String, Object> acceptance = (Map<String, Object>) data.get("acceptance");
        boolean changed = false;
        for (String kind : List.of("browser", "api")) {
            List<Object> checks = (List<Object>) acceptance.get(kind);
            var distinct = new ArrayList<>(new LinkedHashSet<>(checks));
            if (distinct.size() != checks.size()) {
                acceptance.put(kind, distinct);
                if (kind.equals("browser")) {
                    var seen = new HashSet<Object>();
                    var ids = checks.stream().filter(check -> !seen.add(check))
                            .filter(check -> check instanceof Map<?, ?>).map(check -> ((Map<?, ?>) check).get("id"))
                            .filter(id -> id instanceof String text && text.matches("[A-Za-z0-9_-]{1,64}"))
                            .limit(24).toList();
                    log.info("planner_duplicate_browser_checks_dropped ids={} count={}", ids, checks.size() - distinct.size());
                }
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

    /** Units listing the same exact file collapse into the first declared one, which inherits their files, edges,
     * checks and api/route owners. Protected files, v2 batches, unsafe edges and large merges stay for the validator. */
    @SuppressWarnings("unchecked")
    private static boolean mergeUnitsSharingFiles(Map<String, Object> data) {
        if (data.get("batches") != null && !(data.get("batches") instanceof List<?> batches && batches.isEmpty())
                || !(data.get("units") instanceof List<?> declared) || !(data.get("routes") instanceof List<?> routes)
                || !(data.get("api") instanceof List<?> api)
                || data.get("assets") != null && !(data.get("assets") instanceof List<?>))
            return false;
        int size = declared.size();
        var parent = new int[size];
        var firstOwner = new HashMap<String, Integer>();
        var ids = new HashSet<String>();
        for (int i = 0; i < size; i++) {
            if (!(declared.get(i) instanceof Map<?, ?> unit) || !(unit.get("id") instanceof String id) || !ids.add(id)
                    || !(unit.get("objective") instanceof String) || !strings(unit.get("ownedPaths"))
                    || !strings(unit.get("imports")) || !strings(unit.get("dependsOn"))
                    || unit.get("acceptanceIds") != null && !strings(unit.get("acceptanceIds")))
                return false;
            parent[i] = i;
            for (Object path : (List<?>) unit.get("ownedPaths")) {
                Integer prior = firstOwner.putIfAbsent((String) path, i);
                int a = root(parent, i), b = prior == null ? a : root(parent, prior);
                parent[Math.max(a, b)] = Math.min(a, b);
            }
        }
        var groups = new LinkedHashMap<Integer, List<Map<String, Object>>>();
        for (int i = 0; i < size; i++)
            groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add((Map<String, Object>) declared.get(i));
        var units = new ArrayList<Map<String, Object>>((List<Map<String, Object>>) declared);
        var assets = data.get("assets") instanceof List<?> declaredAssets ? declaredAssets : List.of();
        boolean changed = false;
        for (var members : groups.values()) {
            if (members.size() < 2) continue;
            var group = new LinkedHashSet<String>();
            members.forEach(unit -> group.add((String) unit.get("id")));
            String survivor = group.getFirst();
            Set<String> paths = new LinkedHashSet<>(), imports = new LinkedHashSet<>(), checks = new LinkedHashSet<>(),
                    shared = new LinkedHashSet<>(), dependencies = new LinkedHashSet<>(), objectives = new LinkedHashSet<>();
            for (var unit : members) {
                for (String path : (List<String>) unit.get("ownedPaths")) if (!paths.add(path)) shared.add(path);
                imports.addAll((List<String>) unit.get("imports"));
                checks.addAll((List<String>) unit.getOrDefault("acceptanceIds", List.of()));
                dependencies.addAll((List<String>) unit.get("dependsOn"));
                objectives.add(((String) unit.get("objective")).strip().replaceFirst("[.;\\s]+$", ""));
            }
            dependencies.removeAll(group);
            String objective = String.join("; ", objectives);
            // A member's own self-edge is a model error, not an intra-group edge the merge may drop.
            if (paths.size() > 6 || imports.size() > 20 || checks.size() > 48 || dependencies.size() > 12
                    || objective.isBlank() || objective.length() > 1400
                    || members.stream().anyMatch(unit -> ((List<?>) unit.get("dependsOn")).contains(unit.get("id")))
                    || paths.stream().map(path -> path.toLowerCase(Locale.ROOT)).distinct().count() != paths.size()
                    || paths.stream().anyMatch(ContractAcceptanceCompiler::protectedPath)
                    || assets.stream().anyMatch(asset -> asset instanceof Map<?, ?> value
                            && group.contains(value.get("owner")) && !survivor.equals(value.get("owner"))))
                continue;
            var graph = new LinkedHashMap<String, Set<String>>();
            for (var unit : units) {
                String id = (String) unit.get("id");
                if (group.contains(id)) graph.put(survivor, dependencies);
                else graph.put(id, remap((List<String>) unit.get("dependsOn"), group, survivor));
            }
            if (!acyclic(graph)) continue;
            for (var unit : units)
                if (!group.contains(unit.get("id")) && ((List<?>) unit.get("dependsOn")).stream().anyMatch(group::contains))
                    unit.put("dependsOn", new ArrayList<>(graph.get(unit.get("id"))));
            var first = members.getFirst();
            first.put("objective", objective);
            first.put("ownedPaths", new ArrayList<>(paths));
            first.put("imports", new ArrayList<>(imports));
            first.put("dependsOn", new ArrayList<>(dependencies));
            first.put("acceptanceIds", new ArrayList<>(checks));
            for (Object value : routes) remapOwner(value, group, survivor);
            for (Object value : api) remapOwner(value, group, survivor);
            units.removeIf(unit -> unit != first && group.contains(unit.get("id")));
            var merged = group.stream().skip(1).map(id -> id.matches("[A-Za-z0-9_-]{1,64}") ? id : "<unsafe>").toList();
            log.info("planner_units_merged unit={} merged={} paths={}",
                    survivor.matches("[A-Za-z0-9_-]{1,64}") ? survivor : "<unsafe>", merged,
                    shared.stream().filter(path -> path.matches("[A-Za-z0-9_./-]{1,240}")).limit(6).toList());
            changed = true;
        }
        if (changed) data.put("units", units);
        return changed;
    }

    private static boolean strings(Object value) {
        return value instanceof List<?> list && list.stream().allMatch(item -> item instanceof String);
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) index = parent[index];
        return index;
    }

    private static boolean protectedPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("client/src/rendra/") || lower.startsWith("server/src/rendra/")
                || BuildContract.reserved(path, BuildContract.Stack.MERN)
                || path.equalsIgnoreCase(StaticAssets.MARKER) || path.equalsIgnoreCase("rendra.css");
    }

    private static Set<String> remap(List<String> dependencies, Set<String> group, String survivor) {
        var result = new LinkedHashSet<String>();
        dependencies.forEach(id -> result.add(group.contains(id) ? survivor : id));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void remapOwner(Object value, Set<String> group, String survivor) {
        if (value instanceof Map<?, ?> entry && group.contains(entry.get("owner")))
            ((Map<String, Object>) entry).put("owner", survivor);
    }

    /** Unknown dependency ids are left for the validator; only declared edges can form a cycle. */
    private static boolean acyclic(Map<String, Set<String>> graph) {
        var done = new HashSet<String>();
        while (done.size() < graph.size()) {
            var wave = graph.entrySet().stream().filter(node -> !done.contains(node.getKey()) && node.getValue().stream()
                    .allMatch(id -> done.contains(id) || !graph.containsKey(id))).map(Map.Entry::getKey).toList();
            if (wave.isEmpty()) return false;
            done.addAll(wave);
        }
        return true;
    }

    /** A router file no unit owns gets one dedicated server unit (the valid-sample shape), never a page unit's grant.
     * Mongo models come from the coordinator models module, so the unit owns only the router file. */
    @SuppressWarnings("unchecked")
    private static boolean synthesizeRouterUnits(Map<String, Object> data) {
        if (!(data.get("api") instanceof List<?> entries) || !(data.get("units") instanceof List<?> declared)
                || !(data.get("limits") instanceof Map<?, ?> limits) || !(limits.get("modelCalls") instanceof Number calls))
            return false;
        var unitIds = new HashSet<String>();
        var owned = new ArrayList<String>();
        for (Object value : declared) {
            if (!(value instanceof Map<?, ?> unit) || !(unit.get("id") instanceof String id)
                    || !(unit.get("ownedPaths") instanceof List<?> paths)) return false;
            unitIds.add(id);
            for (Object path : paths) if (path instanceof String file) owned.add(file.toLowerCase(Locale.ROOT));
        }
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry) || !(entry.get("routerPath") instanceof String file)) return false;
            groups.computeIfAbsent(file, key -> new ArrayList<>()).add((Map<String, Object>) entry);
        }
        var units = new ArrayList<Object>(declared);
        for (var group : groups.entrySet()) {
            String file = group.getKey(), lower = file.toLowerCase(Locale.ROOT);
            var members = group.getValue();
            if (!(members.getFirst().get("routerExport") instanceof String id)
                    || !(members.getFirst().get("basePath") instanceof String base)
                    || !base.matches("/api(?:/[A-Za-z0-9_/-]{1,100})?") || base.matches("/api/auth(?:/.*)?")
                    || members.stream().anyMatch(entry -> !id.equals(entry.get("routerExport"))
                            || !base.equals(entry.get("basePath")) || entry.get("owner") != null
                            && !(entry.get("owner") instanceof String owner && unitIds.contains(owner))
                            || !(entry.get("path") instanceof String path) || path.matches("/api/auth(?:/.*)?"))
                    || !file.startsWith("server/src/") || !file.endsWith(".ts")
                    || lower.startsWith("server/src/rendra/") || lower.startsWith("client/src/rendra/")
                    || BuildContract.reserved(file, BuildContract.Stack.MERN) || !valid(() -> BuildContract.canonical(file))
                    || owned.stream().anyMatch(path -> path.equals(lower) || path.startsWith(lower + "/")
                            || lower.startsWith(path + "/"))
                    || unitIds.contains(id) || !valid(() -> BuildContract.identifier(id))
                    || units.size() + 1 > 12 || units.size() + 2 > calls.intValue())
                continue;
            var labels = members.stream().map(entry -> entry.get("id")).filter(label ->
                    label instanceof String text && text.matches("[A-Za-z0-9_/:.-]{1,120}")).limit(24).toList();
            var previous = members.stream().map(entry -> entry.get("owner")).filter(owner ->
                    owner instanceof String text && text.matches("[A-Za-z0-9_-]{1,64}")).distinct().toList();
            var unit = new LinkedHashMap<String, Object>();
            unit.put("id", id);
            unit.put("objective", "Implement " + id + " Express router for " + base);
            unit.put("ownedPaths", List.of(file));
            unit.put("imports", List.of());
            unit.put("dependsOn", List.of());
            unit.put("acceptanceIds", List.of());
            units.add(unit);
            unitIds.add(id);
            owned.add(lower);
            members.forEach(entry -> entry.put("owner", id));
            log.info("planner_server_unit_synthesized routerPath={} unit={} ids={} previous={}",
                    file.matches("[A-Za-z0-9_./-]{1,240}") ? file : "<unsafe>", id, labels, previous);
        }
        if (units.size() == declared.size()) return false;
        data.put("units", units);
        return true;
    }

    private static boolean valid(Runnable check) {
        try {
            check.run();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** api/route owners are routing metadata, never a grant: point them at the one unit listing their exact file.
     * Unowned or multiply-owned files, undeclared owners and inconsistent routers stay for the validator. */
    @SuppressWarnings("unchecked")
    private static boolean alignOwnersToFiles(Map<String, Object> data, boolean api) {
        String pathKey = api ? "routerPath" : "componentPath";
        if (!(data.get(api ? "api" : "routes") instanceof List<?> entries) || !(data.get("units") instanceof List<?> units))
            return false;
        var unitIds = new HashMap<String, Integer>();
        var fileOwners = new HashMap<String, List<String>>();
        for (Object value : units) {
            if (!(value instanceof Map<?, ?> unit) || !(unit.get("id") instanceof String id)
                    || !(unit.get("ownedPaths") instanceof List<?> paths)) return false;
            unitIds.merge(id, 1, Integer::sum);
            for (Object path : new LinkedHashSet<>(paths))
                if (path instanceof String file) fileOwners.computeIfAbsent(file, key -> new ArrayList<>()).add(id);
        }
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry) || !(entry.get(pathKey) instanceof String file)) return false;
            groups.computeIfAbsent(file, key -> new ArrayList<>()).add((Map<String, Object>) entry);
        }
        boolean changed = false;
        for (var group : groups.entrySet()) {
            var owners = fileOwners.getOrDefault(group.getKey(), List.of());
            var members = group.getValue();
            if (owners.size() != 1 || unitIds.get(owners.getFirst()) != 1
                    || members.stream().anyMatch(entry -> !(entry.get("owner") instanceof String owner)
                            || !unitIds.containsKey(owner))
                    || api && members.stream().map(entry -> Arrays.asList(entry.get("routerExport"), entry.get("basePath")))
                            .distinct().count() != 1)
                continue;
            String owner = owners.getFirst();
            var moved = members.stream().filter(entry -> !owner.equals(entry.get("owner"))).toList();
            if (moved.isEmpty()) continue;
            var labels = moved.stream().map(entry -> entry.get(api ? "id" : "path")).filter(label ->
                    label instanceof String text && text.matches("[A-Za-z0-9_/:.-]{1,120}")).limit(24).toList();
            var previous = moved.stream().map(entry -> (String) entry.get("owner"))
                    .filter(text -> text.matches("[A-Za-z0-9_-]{1,64}")).distinct().toList();
            moved.forEach(entry -> entry.put("owner", owner));
            String file = group.getKey().matches("[A-Za-z0-9_./-]{1,240}") ? group.getKey() : "<unsafe>";
            String target = owner.matches("[A-Za-z0-9_-]{1,64}") ? owner : "<unsafe>";
            if (api) log.info("planner_api_owner_aligned routerPath={} owner={} ids={} previous={}", file, target, labels, previous);
            else log.info("planner_route_owner_aligned componentPath={} owner={} paths={} previous={}", file, target, labels, previous);
            changed = true;
        }
        return changed;
    }

    /** A 2xx asserted READ/PERSISTENCE of /api/x/${id} with no declared GET gets GET /api/x/:id only when its
     * PATCH/PUT/DELETE siblings agree on one unit owner, router, guard and declared record type. A NOT_FOUND alone
     * never declares one: it would pass against an unimplemented route. */
    private static boolean declareRecordReads(Map<String, Object> data, List<BehaviorCriteria.ApiStep> checks,
            List<Map<String, Object>> units, ObjectMapper mapper) {
        if (!(data.get("api") instanceof List<?> entries) || !(data.get("types") instanceof List<?> types)) return false;
        var declared = new ArrayList<BuildContract.Api>(entries.stream()
                .map(value -> mapper.convertValue(value, BuildContract.Api.class)).toList());
        var typeNames = types.stream().map(type -> type instanceof Map<?, ?> value ? value.get("name") : null).toList();
        var api = new ArrayList<Object>(entries);
        var shapes = new HashSet<String>();
        for (var check : checks) {
            if (check.kind() != BehaviorCriteria.ApiKind.READ && check.kind() != BehaviorCriteria.ApiKind.PERSISTENCE
                    || !check.method().equals("GET") || check.status() < 200 || check.status() >= 300
                    || check.assertPointer() == null || check.assertValue() == null
                    || !check.path().matches("/api/[A-Za-z0-9_/-]{1,160}/\\$\\{[A-Za-z][A-Za-z0-9_]*}")
                    || declared.stream().anyMatch(endpoint -> endpoint.method().equals("GET")
                            && BuildContract.matchesApiPath(endpoint.path(), check.path())))
                continue;
            var siblings = declared.stream().filter(endpoint -> Set.of("PATCH", "PUT", "DELETE").contains(endpoint.method())
                    && BuildContract.matchesApiPath(endpoint.path(), check.path())).toList();
            if (siblings.isEmpty() || !shapes.add(siblings.getFirst().path())) continue;
            var sibling = siblings.getFirst();
            String shape = sibling.path(), list = shape.substring(0, shape.lastIndexOf('/'));
            var records = siblings.stream().filter(endpoint -> !endpoint.method().equals("DELETE"))
                    .map(BuildContract.Api::responseType).distinct().toList();
            String id = records.size() == 1 ? "get" + records.getFirst() : "";
            if (!shape.matches("/api/[A-Za-z0-9_/-]{1,160}/:[A-Za-z][A-Za-z0-9_]*") || records.size() != 1
                    || records.getFirst().endsWith("[]") || !typeNames.contains(records.getFirst())
                    || siblings.stream().anyMatch(endpoint -> !endpoint.path().equals(shape)
                            || !endpoint.owner().equals(sibling.owner()) || !endpoint.authenticated()
                            || !endpoint.routerPath().equals(sibling.routerPath())
                            || !endpoint.routerExport().equals(sibling.routerExport())
                            || !Objects.equals(endpoint.basePath(), sibling.basePath()))
                    || units.stream().noneMatch(unit -> sibling.owner().equals(unit.get("id")))
                    || declared.stream().anyMatch(endpoint -> endpoint.method().equals("GET") && endpoint.path().equals(list)
                            && !endpoint.responseType().equals(records.getFirst() + "[]"))
                    || !valid(() -> BuildContract.identifier(id))
                    || declared.stream().anyMatch(endpoint -> endpoint.id().equals(id)) || api.size() >= 24)
                continue;
            var read = new BuildContract.Api(id, "GET", shape, null, records.getFirst(), sibling.owner(),
                    sibling.routerPath(), sibling.routerExport(), sibling.basePath(), true);
            declared.add(read);
            api.add(mapper.convertValue(read, Map.class));
            var reads = checks.stream().filter(step -> step.method().equals("GET") && BuildContract.matchesApiPath(shape, step.path()))
                    .map(BehaviorCriteria.ApiStep::id).filter(text -> text.matches("[A-Za-z0-9_-]{1,64}")).limit(8).toList();
            log.info("planner_record_read_declared id={} path={} owner={} responseType={} checks={}", id, shape,
                    sibling.owner(), records.getFirst(), reads);
        }
        if (api.size() == entries.size()) return false;
        data.put("api", api);
        return true;
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
