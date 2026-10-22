package com.code.pal.agent.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ContractDependenciesTest {
    final ObjectMapper mapper = new ObjectMapper();

    Map<String, Object> proposal() {
        return mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()), Map.class);
    }
    List<Map<String, Object>> units(Map<String, Object> data) { return (List<Map<String, Object>>) data.get("units"); }
    Map<String, Object> page(String id, String path, String... features) {
        return Map.of("id", id, "path", path, "featureIds", List.of(features));
    }
    String scope(List<Map<String, Object>> pages, String... pageIds) {
        return mapper.writeValueAsString(Map.of("requestedScope", Map.of("pages", pages,
                "features", List.of(Map.of("id", "task_list", "pageIds", List.of(pageIds))))));
    }

    @Test void uniqueApprovedPageOwnerReplacesFeatureAliasAndRevalidatesWithoutCalls() {
        var data = proposal();
        units(data).getFirst().put("dependsOn", List.of("task_list"));
        units(data).getLast().put("dependsOn", List.of());
        String approved = scope(List.of(page("home", "/", "task_list")), "home");
        String normalized = ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data), mapper, "Todo", approved);
        var contract = mapper.readValue(normalized, BuildContract.class);
        assertEquals(List.of("ui"), contract.units().getFirst().dependsOn());
        assertEquals(ContractFixtures.mern().acceptance(), contract.acceptance());
        assertFalse(ContractDependencies.normalize(mapper.readValue(normalized, Map.class), approved, mapper));
    }

    @Test void multiplePagesWithTheSameOwnerAreUnambiguousAndDuplicateEdgesCollapse() {
        var data = proposal();
        ((List<Object>) data.get("routes")).add(Map.of("path", "/other", "componentPath", "client/src/App.tsx", "owner", "ui"));
        units(data).getFirst().put("dependsOn", List.of("task_list", "ui"));
        assertTrue(ContractDependencies.normalize(data, scope(List.of(page("home", "/"), page("other", "/other")), "home", "other"), mapper));
        assertEquals(List.of("ui"), units(data).getFirst().get("dependsOn"));
    }

    @Test void ambiguousUnknownMissingPageAndUnownedRoutesRemainRejected() {
        for (String variant : List.of("ambiguous", "unknown", "missing", "unowned")) {
            var data = proposal();
            units(data).getFirst().put("dependsOn", List.of(variant.equals("unknown") ? "unknown_feature" : "task_list"));
            var pages = new ArrayList<>(List.of(page("home", "/", "task_list")));
            if (variant.equals("ambiguous")) {
                pages.add(page("other", "/other", "task_list"));
                ((List<Object>) data.get("routes")).add(Map.of("path", "/other", "componentPath", "server/src/items.ts", "owner", "data"));
            }
            if (variant.equals("unowned")) ((List<Map<String, Object>>) data.get("routes")).getFirst().put("owner", "data");
            String approved = scope(pages, variant.equals("missing") ? "missing" : "home");
            assertFalse(ContractDependencies.normalize(data, approved, mapper), variant);
            assertThrows(RuntimeException.class, () -> mapper.readValue(mapper.writeValueAsString(data), BuildContract.class), variant);
        }
    }

    @Test void changedAnswersAndLegacyTextCannotAuthorizeAliasMapping() {
        for (String approved : List.of("legacy scope", "{\"answersChanged\":true,\"requestedScope\":{\"pages\":[],\"features\":[]}}")) {
            var data = proposal(); units(data).getFirst().put("dependsOn", List.of("task_list"));
            assertFalse(ContractDependencies.normalize(data, approved, mapper));
        }
    }

    @Test void selfEdgesAndRealUnitIdsAreNotSilentlyRemovedOrReinterpreted() {
        var data = proposal(); units(data).getLast().put("dependsOn", List.of("task_list"));
        assertTrue(ContractDependencies.normalize(data, scope(List.of(page("home", "/", "task_list")), "home"), mapper));
        assertEquals(List.of("ui"), units(data).getLast().get("dependsOn"));
        assertThrows(RuntimeException.class, () -> mapper.readValue(mapper.writeValueAsString(data), BuildContract.class));
        units(data).getLast().put("dependsOn", List.of("data"));
        String collision = scope(List.of(page("home", "/", "task_list")), "home").replace("task_list", "data");
        assertFalse(ContractDependencies.normalize(data, collision, mapper));
        assertEquals(List.of("data"), units(data).getLast().get("dependsOn"));
    }
}
