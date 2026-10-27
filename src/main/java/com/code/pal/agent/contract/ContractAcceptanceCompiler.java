package com.code.pal.agent.contract;

import com.code.pal.agent.validation.AccountAcceptance;

import com.code.pal.agent.validation.BehaviorCriteria;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Completes bounded MERN acceptance ownership and removes unit claims on coordinator-owned files.
 * It adds no permission, implementation or acceptance check beyond the existing CRUD fixture, no endpoint except the
 * record GET that the contract's own read or not-found checks request, and no check except that record's READ mirrored
 * from an existing grounded list READ. */
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
    private static final Pattern ADD_CLICK = Pattern.compile("ui_(?:create|add)(?:_[a-z0-9]+)*");
    private static final Pattern EDIT_CLICK = Pattern.compile("ui_edit(?:_[a-z0-9]+)*");
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
        changed |= dropBuiltInTypes(data);
        if (!"MERN".equals(data.get("stack")))
            return changed ? mapper.writeValueAsString(data) : content;
        changed |= removeExactDuplicates(data);
        changed |= renameDuplicateApiIds(data);
        changed |= narrowRouterBases(data);
        changed |= resolveUnambiguousUnitIds(data);
        changed |= alignAddClickId(data);
        changed |= alignEditClickId(data);
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
        var checks = new ArrayList<>(criteria.api());
        changed |= declareRecordReads(data, checks, criteria.browser(), units, mapper);
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
        changed |= shareEditChecks(units, frontend, criteria.browser(), (List<?>) data.get("routes"), mapper);
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

    /** Units write a router relative to its resource ('/' and '/:id'), so a router mounted at bare /api whose every
     * path continues with one shared static segment is mounted at that resource instead. Paths are unchanged; another
     * router already at that base, a path equal to /api or mixed segments leave it for the validator. */
    @SuppressWarnings("unchecked")
    private static boolean narrowRouterBases(Map<String, Object> data) {
        if (!(data.get("api") instanceof List<?> api))
            return false;
        var routers = new LinkedHashMap<String, List<Map<String, Object>>>();
        var bases = new HashSet<Object>();
        for (Object value : api) {
            if (!(value instanceof Map<?, ?> entry) || !(entry.get("routerPath") instanceof String router))
                return false;
            routers.computeIfAbsent(router, key -> new ArrayList<>()).add((Map<String, Object>) entry);
            bases.add(entry.get("basePath"));
        }
        boolean changed = false;
        for (var members : routers.values()) {
            String segment = null;
            for (var entry : members) {
                String next = "/api".equals(entry.get("basePath")) && entry.get("path") instanceof String path
                        && path.startsWith("/api/") ? path.substring(5).split("/", 2)[0] : null;
                if (next == null || !next.matches("[A-Za-z0-9_-]{1,60}") || segment != null && !segment.equals(next)) {
                    segment = null;
                    break;
                }
                segment = next;
            }
            if (segment == null || !bases.add("/api/" + segment))
                continue;
            for (var entry : members)
                entry.put("basePath", "/api/" + segment);
            log.info("planner_router_base_narrowed router={} to=/api/{}", members.getFirst().get("routerPath"), segment);
            changed = true;
        }
        return changed;
    }

    /** The coordinator's types.ts already declares the built-ins, so a model copy no api references would collide with
     * it at scaffold time. A referenced copy is left for the validator: its shape may differ from the built-in. */
    private static boolean dropBuiltInTypes(Map<String, Object> data) {
        if ("STATIC".equals(data.get("stack")) || !(data.get("types") instanceof List<?> types))
            return false;
        var used = new HashSet<String>();
        if (data.get("api") instanceof List<?> api)
            for (Object value : api)
                if (value instanceof Map<?, ?> endpoint)
                    for (String key : List.of("requestType", "responseType"))
                        if (endpoint.get(key) instanceof String type)
                            used.add(type.endsWith("[]") ? type.substring(0, type.length() - 2) : type);
        var kept = new ArrayList<Object>();
        var dropped = new ArrayList<String>();
        for (Object value : types)
            if (value instanceof Map<?, ?> type && type.get("name") instanceof String name
                    && BuildContract.BUILT_IN_TYPES.contains(name) && !used.contains(name))
                dropped.add(name);
            else
                kept.add(value);
        if (dropped.isEmpty())
            return false;
        data.put("types", kept);
        log.info("planner_built_in_types_dropped names={}", dropped);
        return true;
    }

    /** A uniquely named create/add click that a FILL prepares and ui_created asserts is the required ui_add; only its id changes. */
    @SuppressWarnings("unchecked")
    private static boolean alignAddClickId(Map<String, Object> data) {
        if (!(data.get("acceptance") instanceof Map<?, ?> acceptance) || !(acceptance.get("browser") instanceof List<?> browser)
                || !(data.get("units") instanceof List<?> units)) return false;
        var ids = new HashSet<String>();
        int candidate = -1, created = -1;
        for (int i = 0; i < browser.size(); i++) {
            if (!(browser.get(i) instanceof Map<?, ?> action) || !(action.get("id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) return false;
            if (id.equals("ui_created")) created = i;
            if ("CLICK".equals(action.get("kind")) && ADD_CLICK.matcher(id).matches() && !REQUIRED_UI_IDS.contains(id)) {
                if (candidate >= 0) return false;
                candidate = i;
            }
        }
        if (candidate < 0 || created < candidate || ids.contains("ui_add")
                || !"ASSERT_TEXT".equals(((Map<?, ?>) browser.get(created)).get("kind"))
                || browser.subList(candidate + 1, created).stream().anyMatch(action ->
                        Set.of("CLICK", "SELECT", "RELOAD").contains(((Map<?, ?>) action).get("kind")))
                || browser.subList(0, candidate).stream().noneMatch(action -> "FILL".equals(((Map<?, ?>) action).get("kind"))))
            return false;
        String oldId = renameBrowserId(browser, units, candidate, "ui_add");
        if (oldId == null) return false;
        log.info("planner_browser_add_id_aligned from={} to=ui_add", oldId);
        return true;
    }

    /** The one edit click that a FILL after ui_created prepares and ui_edited directly asserts is the required ui_edit;
     * an edit-opening click before that FILL keeps its id. Only the id changes. */
    private static boolean alignEditClickId(Map<String, Object> data) {
        if (!(data.get("acceptance") instanceof Map<?, ?> acceptance) || !(acceptance.get("browser") instanceof List<?> browser)
                || !(data.get("units") instanceof List<?> units)) return false;
        var ids = new HashSet<String>();
        int created = -1, edited = -1;
        for (int i = 0; i < browser.size(); i++) {
            if (!(browser.get(i) instanceof Map<?, ?> action) || !(action.get("id") instanceof String id)
                    || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id)) return false;
            if (id.equals("ui_created")) created = i;
            if (id.equals("ui_edited")) edited = i;
        }
        if (created < 0 || edited < created || ids.contains("ui_edit")
                || !"ASSERT_TEXT".equals(((Map<?, ?>) browser.get(edited)).get("kind"))) return false;
        int candidate = -1;
        boolean filled = false;
        for (int i = created + 1; i < edited; i++) {
            var action = (Map<?, ?>) browser.get(i);
            if ("FILL".equals(action.get("kind"))) filled = true;
            else if (filled && "CLICK".equals(action.get("kind")) && EDIT_CLICK.matcher((String) action.get("id")).matches()) {
                if (candidate >= 0) return false;
                candidate = i;
            }
        }
        if (candidate < 0 || browser.subList(candidate + 1, edited).stream().anyMatch(action ->
                Set.of("CLICK", "SELECT", "RELOAD").contains(((Map<?, ?>) action).get("kind")))) return false;
        String oldId = renameBrowserId(browser, units, candidate, "ui_edit");
        if (oldId == null) return false;
        log.info("planner_browser_edit_id_aligned from={} to=ui_edit", oldId);
        return true;
    }

    /** Renames one browser action and its unit acceptanceIds; null (unchanged) when any unit list is malformed or has the target. */
    @SuppressWarnings("unchecked")
    private static String renameBrowserId(List<?> browser, List<?> units, int index, String target) {
        for (Object unit : units)
            if (!(unit instanceof Map<?, ?> owner) || owner.get("acceptanceIds") != null
                    && (!strings(owner.get("acceptanceIds")) || ((List<?>) owner.get("acceptanceIds")).contains(target)))
                return null;
        var action = (Map<String, Object>) browser.get(index);
        String oldId = (String) action.get("id");
        action.put("id", target);
        for (Object unit : units) {
            var owner = (Map<String, Object>) unit;
            if (!(owner.get("acceptanceIds") instanceof List<?> assigned) || !assigned.contains(oldId)) continue;
            var renamed = new ArrayList<>((List<String>) assigned);
            renamed.replaceAll(id -> id.equals(oldId) ? target : id);
            owner.put("acceptanceIds", renamed);
        }
        return oldId;
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

    /** api ids are referenced by nothing in the contract; a repeated id becomes each endpoint's own method_path name only
     * when every name is valid and distinct. */
    @SuppressWarnings("unchecked")
    private static boolean renameDuplicateApiIds(Map<String, Object> data) {
        if (!(data.get("api") instanceof List<?> entries)) return false;
        var groups = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (Object value : entries) {
            if (!(value instanceof Map<?, ?> entry) || !(entry.get("id") instanceof String id)
                    || !(entry.get("method") instanceof String) || !(entry.get("path") instanceof String)) return false;
            groups.computeIfAbsent(id, key -> new ArrayList<>()).add((Map<String, Object>) entry);
        }
        var taken = new HashSet<String>();
        groups.entrySet().removeIf(group -> group.getValue().size() < 2 && taken.add(group.getKey()));
        if (groups.isEmpty()) return false;
        var names = new ArrayList<List<String>>();
        for (var members : groups.values()) {
            var renamed = new ArrayList<String>();
            for (var entry : members) {
                String path = (String) entry.get("path");
                String rest = path.startsWith("/api/") ? path.substring("/api/".length()).replace(":", "")
                        .replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "") : "";
                String name = ((String) entry.get("method")).toLowerCase(Locale.ROOT) + "_" + rest;
                if (rest.isEmpty() || !valid(() -> BuildContract.identifier(name)) || !taken.add(name)) return false;
                renamed.add(name);
            }
            names.add(renamed);
        }
        int index = 0;
        for (var group : groups.entrySet()) {
            var renamed = names.get(index++);
            for (int i = 0; i < renamed.size(); i++) group.getValue().get(i).put("id", renamed.get(i));
            String old = group.getKey();
            log.info("planner_duplicate_api_ids_renamed id={} ids={} count={}",
                    old.matches("[A-Za-z0-9_-]{1,64}") ? old : "<unsafe>", renamed, renamed.size());
        }
        return true;
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
     * never declares one: it would pass against an unimplemented route. A NOT_FOUND of /api/x/${id} after its DELETE
     * declares it only with one CREATE of /api/x capturing id and asserting a field, and one list READ of /api/x before
     * the first UPDATE/DELETE asserting that field and value at /0; that READ is mirrored onto the record path. */
    private static boolean declareRecordReads(Map<String, Object> data, List<BehaviorCriteria.ApiStep> checks,
            List<BehaviorCriteria.BrowserAction> browser, List<Map<String, Object>> units, ObjectMapper mapper) {
        if (!(data.get("api") instanceof List<?> entries) || !(data.get("types") instanceof List<?> types)) return false;
        var declared = new ArrayList<BuildContract.Api>(entries.stream()
                .map(value -> mapper.convertValue(value, BuildContract.Api.class)).toList());
        var typeNames = types.stream().map(type -> type instanceof Map<?, ?> value ? value.get("name") : null).toList();
        var api = new ArrayList<Object>(entries);
        var shapes = new HashSet<String>();
        for (var check : List.copyOf(checks)) {
            boolean missing = check.kind() == BehaviorCriteria.ApiKind.NOT_FOUND && check.method().equals("GET")
                    && check.status() == 404;
            if (!missing && (check.kind() != BehaviorCriteria.ApiKind.READ && check.kind() != BehaviorCriteria.ApiKind.PERSISTENCE
                    || !check.method().equals("GET") || check.status() < 200 || check.status() >= 300
                    || check.assertPointer() == null || check.assertValue() == null)
                    || !check.path().matches("/api/[A-Za-z0-9_/-]{1,160}/\\$\\{[A-Za-z][A-Za-z0-9_]*}")
                    || declared.stream().anyMatch(endpoint -> endpoint.method().equals("GET")
                            && BuildContract.matchesApiPath(endpoint.path(), check.path())))
                continue;
            var siblings = declared.stream().filter(endpoint -> Set.of("PATCH", "PUT", "DELETE").contains(endpoint.method())
                    && BuildContract.matchesApiPath(endpoint.path(), check.path())).toList();
            // A 2xx read of the record shape owns the case through the first trigger.
            if (siblings.isEmpty() || missing && checks.stream().anyMatch(step -> step.method().equals("GET")
                    && step.status() >= 200 && step.status() < 300
                    && BuildContract.matchesApiPath(siblings.getFirst().path(), step.path()))
                    || !shapes.add(siblings.getFirst().path())) continue;
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
            BehaviorCriteria.ApiStep source = null, mirror = null;
            if (missing) {
                String path = check.path(), variable = path.substring(path.lastIndexOf("${") + 2, path.length() - 1);
                String collection = path.substring(0, path.lastIndexOf('/')), step = "record_" + id;
                int at = checks.indexOf(check);
                var creates = checks.stream().filter(value -> value.kind() == BehaviorCriteria.ApiKind.CREATE
                        && value.method().equals("POST") && value.path().equals(collection)).toList();
                if (creates.size() != 1 || !variable.equals(creates.getFirst().captureVariable()) || !collection.equals(list) || checks.size() >= 24
                        || !step.matches("[A-Za-z0-9_-]{1,64}")
                        || checks.stream().anyMatch(value -> value.id().equals(step))
                        || browser.stream().anyMatch(value -> step.equals(value.id()))
                        || checks.subList(0, at).stream().noneMatch(value -> value.kind() == BehaviorCriteria.ApiKind.DELETE
                                && value.method().equals("DELETE") && value.path().equals(path))
                        || declared.stream().noneMatch(endpoint -> endpoint.method().equals("GET")
                                && endpoint.path().equals(list) && endpoint.responseType().equals(records.getFirst() + "[]")))
                    continue;
                var create = creates.getFirst();
                int from = checks.indexOf(create), until = from + 1;
                while (until < checks.size() && !(Set.of(BehaviorCriteria.ApiKind.UPDATE, BehaviorCriteria.ApiKind.DELETE)
                        .contains(checks.get(until).kind()) && checks.get(until).path().equals(path))) until++;
                var lists = checks.subList(from + 1, until).stream().filter(value -> value.kind() == BehaviorCriteria.ApiKind.READ
                        && value.method().equals("GET") && value.path().equals(collection)).toList();
                if (until == checks.size() || lists.size() != 1) continue;
                source = lists.getFirst();
                if (source.status() < 200 || source.status() >= 300 || source.assertPointer() == null
                        || !source.assertPointer().matches("/0/[A-Za-z0-9_]+") || source.assertValue() == null
                        || !source.assertPointer().substring(2).equals(create.assertPointer())
                        || !Objects.equals(create.assertValue(), source.assertValue()))
                    continue;
                mirror = new BehaviorCriteria.ApiStep(step, BehaviorCriteria.ApiKind.READ, "GET", path, null, 200,
                        source.tokenVariable(), null, null, create.assertPointer(), create.assertValue());
                checks.add(checks.indexOf(source) + 1, mirror);
            }
            var read = new BuildContract.Api(id, "GET", shape, null, records.getFirst(), sibling.owner(),
                    sibling.routerPath(), sibling.routerExport(), sibling.basePath(), true);
            declared.add(read);
            api.add(mapper.convertValue(read, Map.class));
            var reads = checks.stream().filter(step -> step.method().equals("GET") && BuildContract.matchesApiPath(shape, step.path()))
                    .map(BehaviorCriteria.ApiStep::id).filter(text -> text.matches("[A-Za-z0-9_-]{1,64}")).limit(8).toList();
            log.info("planner_record_read_declared id={} path={} owner={} responseType={} checks={}", id, shape,
                    sibling.owner(), records.getFirst(), reads);
            if (mirror != null)
                log.info("planner_record_read_check_inserted id={} path={} from={}", mirror.id(), shape,
                        source.id().matches("[A-Za-z0-9_-]{1,64}") ? source.id() : "<unsafe>");
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

    /** Browser checks go to the "/" owner, but when the only edit route belongs to another unit, the edit is typed and
     * submitted on that unit's page: it also receives those FILL/SELECT steps and ui_edit, so it renders their selectors
     * and is repaired when they fail. Nothing is removed from the "/" owner. */
    @SuppressWarnings("unchecked")
    private static boolean shareEditChecks(List<Map<String, Object>> units, String frontend,
            List<BehaviorCriteria.BrowserAction> browser, List<?> routes, ObjectMapper mapper) {
        var editors = routes.stream().map(value -> mapper.convertValue(value, BuildContract.Route.class))
                .filter(route -> route.path().endsWith("/edit")).map(BuildContract.Route::owner).distinct().toList();
        if (frontend == null || editors.size() != 1 || editors.getFirst().equals(frontend)
                || !editors.getFirst().matches("[A-Za-z0-9_-]{1,64}"))
            return false;
        var ids = browser.stream().map(BehaviorCriteria.BrowserAction::id).toList();
        int created = ids.indexOf("ui_created"), edit = ids.indexOf("ui_edit"), edited = ids.indexOf("ui_edited");
        if (created < 0 || edit <= created || edited <= edit)
            return false;
        var editor = units.stream().filter(unit -> editors.getFirst().equals(unit.get("id"))).findFirst().orElse(null);
        if (editor == null)
            return false;
        var assigned = new ArrayList<>((List<String>) editor.get("acceptanceIds"));
        var shared = new ArrayList<String>();
        for (int i = created + 1; i <= edit; i++)
            if ((i == edit || Set.of(BehaviorCriteria.BrowserKind.FILL, BehaviorCriteria.BrowserKind.SELECT)
                    .contains(browser.get(i).kind())) && !assigned.contains(ids.get(i)) && ids.get(i).matches("[A-Za-z0-9_-]{1,64}"))
                shared.add(ids.get(i));
        if (shared.isEmpty() || assigned.size() + shared.size() > 48)
            return false;
        assigned.addAll(shared);
        editor.put("acceptanceIds", assigned);
        log.info("planner_edit_checks_shared owner={} ids={}", editors.getFirst(), shared);
        return true;
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
