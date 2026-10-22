package com.code.pal.agent.contract;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Resolves feature aliases only from approved pages and exact declared file ownership. */
@Slf4j
final class ContractDependencies {
    private ContractDependencies() {}

    @SuppressWarnings("unchecked")
    static boolean normalize(Map<String, Object> data, String approved, ObjectMapper mapper) {
        JsonNode scope;
        try {
            var envelope = mapper.readTree(approved);
            if (envelope == null || envelope.path("answersChanged").asBoolean(false)) return false;
            scope = envelope.has("requestedScope") ? envelope.path("requestedScope") : envelope;
        } catch (RuntimeException legacy) { return false; }
        if (!scope.path("pages").isArray() || !scope.path("features").isArray()
                || !(data.get("units") instanceof List<?> units) || !(data.get("routes") instanceof List<?> routes)) return false;
        var unitIds = new HashSet<Object>();
        var pathOwners = new HashMap<Object, Set<Object>>();
        for (Object value : units) {
            if (!(value instanceof Map<?, ?> unit) || !(unit.get("ownedPaths") instanceof List<?> paths)) return false;
            if (!unitIds.add(unit.get("id"))) return false; // Duplicate identities must be resolved first.
            for (Object path : paths) pathOwners.computeIfAbsent(path, ignored -> new HashSet<>()).add(unit.get("id"));
        }
        var aliases = new HashMap<String, String>();
        for (var feature : scope.path("features")) {
            String id = feature.path("id").asString("");
            if (!id.matches("[A-Za-z0-9_-]{1,48}") || unitIds.contains(id)) continue;
            var pages = new LinkedHashSet<String>();
            feature.path("pageIds").forEach(page -> pages.add(page.asString("")));
            for (var page : scope.path("pages"))
                for (var ref : page.path("featureIds"))
                    if (id.equals(ref.asString(""))) pages.add(page.path("id").asString(""));
            var owners = new HashSet<String>();
            boolean complete = !pages.isEmpty();
            for (String pageId : pages) {
                var matched = new ArrayList<JsonNode>();
                for (var page : scope.path("pages")) if (pageId.equals(page.path("id").asString(""))) matched.add(page);
                if (matched.size() != 1) { complete = false; break; }
                String path = matched.getFirst().path("path").asString("");
                var pageOwners = new HashSet<String>();
                for (Object value : routes) {
                    if (!(value instanceof Map<?, ?> route) || !path.equals(route.get("path"))) continue;
                    Object owner = route.get("owner");
                    if (owner instanceof String name && unitIds.contains(name)
                            && Set.of(name).equals(pathOwners.get(route.get("componentPath")))) pageOwners.add(name);
                    else complete = false;
                }
                if (pageOwners.size() != 1) complete = false;
                owners.addAll(pageOwners);
            }
            if (complete && owners.size() == 1) aliases.put(id, owners.iterator().next());
        }
        boolean changed = false;
        for (Object value : units) {
            var unit = (Map<String, Object>) value;
            if (!(unit.get("dependsOn") instanceof List<?> dependencies)) continue;
            var normalized = new LinkedHashSet<Object>();
            for (Object dependency : dependencies) {
                String owner = aliases.get(dependency);
                normalized.add(owner == null ? dependency : owner);
                if (owner != null) {
                    log.info("planner_dependency_feature_resolved unit={} feature={} owner={}", unit.get("id"), dependency, owner);
                    changed = true;
                }
            }
            if (dependencies.stream().anyMatch(aliases::containsKey)) unit.put("dependsOn", new ArrayList<>(normalized));
        }
        // Self-edges and cycles remain validator failures; aliases never grant implementation or remove prerequisites.
        return changed;
    }
}
