package com.code.pal.agent.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Located validator feedback names the rejected value; the no-progress signature never does. */
@SuppressWarnings("unchecked")
class ContractRuleFeedbackTest {
    final ObjectMapper mapper = new ObjectMapper();

    RuntimeException reject(BuildContract base, Consumer<Map<String, Object>> change) {
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsString(base), Map.class);
        change.accept(data);
        String json = mapper.writeValueAsString(data);
        return assertThrows(RuntimeException.class, () -> mapper.readValue(json, BuildContract.class));
    }
    List<Map<String, Object>> list(Map<String, Object> data, String field) { return (List<Map<String, Object>>) data.get(field); }
    void owned(Map<String, Object> data, int unit, String path) {
        var paths = new ArrayList<>((List<String>) list(data, "units").get(unit).get("ownedPaths")); paths.add(path);
        list(data, "units").get(unit).put("ownedPaths", paths);
    }
    void located(String signature, RuntimeException first, String firstValue, RuntimeException second, String secondValue) {
        assertEquals(signature, ContractPlanner.rejectionSignature(first));
        assertEquals(signature, ContractPlanner.rejectionSignature(second));
        String feedback = ContractPlanner.schemaFeedback(first);
        assertTrue(feedback.startsWith(signature + "; ") && feedback.contains(firstValue), feedback);
        assertTrue(ContractPlanner.schemaFeedback(second).contains(secondValue), ContractPlanner.schemaFeedback(second));
        assertFalse(signature.contains(firstValue) || signature.contains(secondValue));
    }

    @Test void routerOwnershipMismatchNamesTheEndpointRouterAndExpectedOwner() {
        var c = ContractFixtures.mern();
        var first = reject(c, data -> list(data, "api").get(0).put("owner", "ui"));
        var second = reject(c, data -> list(data, "api").get(2).put("owner", "ui"));
        located("api: router ownership mismatch", first, "api itemPOST owner ui but routerPath server/src/items.ts is owned by unit data; set owner data",
                second, "api itemPUT owner ui");
        var unowned = reject(c, data -> list(data, "api").forEach(api -> api.put("routerPath", "server/src/notes.ts")));
        assertEquals("api: router ownership mismatch", ContractPlanner.rejectionSignature(unowned));
        assertTrue(ContractPlanner.schemaFeedback(unowned).contains("routerPath server/src/notes.ts is in no unit ownedPaths"));
    }

    @Test void sharedAndCoordinatorFilesAreSeparateRulesNamingThePathAndUnits() {
        var c = ContractFixtures.mern();
        located("ownedPaths: duplicate file owner", reject(c, data -> owned(data, 1, "server/src/items.ts")),
                "server/src/items.ts is listed by units data and ui",
                reject(c, data -> { owned(data, 0, "client/src/Shared.tsx"); owned(data, 1, "client/src/Shared.tsx"); }),
                "client/src/Shared.tsx is listed by units data and ui");
        located("ownedPaths: coordinator-owned path", reject(c, data -> owned(data, 1, "package.json")), "unit ui lists package.json",
                reject(c, data -> owned(data, 0, "server/src/index.ts")), "unit data lists server/src/index.ts");
        var rendra = reject(c, data -> owned(data, 1, "client/src/rendra/" + c.id() + "/api.ts"));
        assertEquals("ownedPaths: coordinator-owned path", ContractPlanner.rejectionSignature(rendra));
    }

    @Test void duplicateIdentifiersNameTheCollectionAndValue() {
        var c = ContractFixtures.mern();
        located("units: duplicate unit id", reject(c, data -> list(data, "units").get(1).put("id", "data")), "duplicate data in units",
                reject(c, data -> { var units = list(data, "units"); units.get(0).put("id", "ui"); }), "duplicate ui in units");
        located("api: duplicate api id", reject(c, data -> list(data, "api").get(1).put("id", "itemPOST")), "duplicate itemPOST in api",
                reject(c, data -> list(data, "api").get(3).put("id", "itemPUT")), "duplicate itemPUT in api");
        var route = reject(c, data -> data.put("routes", List.of(list(data, "routes").getFirst(), list(data, "routes").getFirst())));
        assertEquals("routes: duplicate route path", ContractPlanner.rejectionSignature(route));
    }

    @Test void unassignedCriterionNamesTheStepMethodAndPath() {
        var c = ContractFixtures.mern();
        var request = reject(c, data -> list(data, "units").get(0).put("acceptanceIds", List.of("health", "create", "update", "restart", "delete", "missing")));
        var browser = reject(c, data -> list(data, "units").get(1).put("acceptanceIds", List.of()));
        located("acceptanceIds: criterion has no responsible unit", request, "api read GET /api/items/${itemId} in no unit acceptanceIds",
                browser, "browser ui ASSERT_TEXT in no unit acceptanceIds");
        // MERN acceptanceIds are coordinator-assigned: the feedback points at the api/App owner, never at editing acceptanceIds.
        String api = ContractPlanner.schemaFeedback(request), ui = ContractPlanner.schemaFeedback(browser);
        assertTrue(api.endsWith("for MERN the coordinator assigns acceptanceIds, so each api check needs exactly one declared api"
                + " with that method and path whose owner is a unit id"), api);
        assertTrue(ui.endsWith("for MERN the coordinator assigns acceptanceIds, so browser checks need one unit owning client/src/App.tsx or the / route"), ui);
        assertFalse(api.contains("add each id") || ui.contains("add each id"));
        var vite = reject(ContractFixtures.vite(), data -> list(data, "units").get(0).put("acceptanceIds", List.of()));
        assertEquals("acceptanceIds: criterion has no responsible unit", ContractPlanner.rejectionSignature(vite));
        assertTrue(ContractPlanner.schemaFeedback(vite).endsWith("browser ui ASSERT_TEXT in no unit acceptanceIds; add each id to the unit that implements it"),
                ContractPlanner.schemaFeedback(vite));
    }

    @Test void unguardedAccountApiNamesTheEndpoint() {
        var c = ContractFixtures.accounts();
        located("api: account data API requires auth guard", reject(c, data -> list(data, "api").get(0).put("authenticated", false)),
                "api itemPOST POST /api/items has authenticated false",
                reject(c, data -> list(data, "api").get(3).put("authenticated", false)), "api itemDELETE DELETE /api/items/:id");
    }

    void endpoint(Map<String, Object> data, String id, String method, String path) {
        var api = new ArrayList<>(list(data, "api")); var copy = new LinkedHashMap<>(api.get(1));
        copy.putAll(Map.of("id", id, "method", method, "path", path)); api.add(copy); data.put("api", api);
    }

    @Test void uncoveredEndpointsAreListedTogetherUnderOneStableRule() {
        String rule = "api: declared endpoint has no successful acceptance request";
        var first = reject(ContractFixtures.mern(), data -> endpoint(data, "itemStats", "GET", "/api/items/stats"));
        // MERN must keep an UPDATE check, so it moves to a declared PATCH and leaves itemPUT uncovered.
        var second = reject(ContractFixtures.mern(), data -> {
            var api = new ArrayList<>(list(data, "api")); var patch = new LinkedHashMap<>(api.get(2));
            patch.putAll(Map.of("id", "itemPATCH", "method", "PATCH")); api.add(patch); data.put("api", api);
            list((Map<String, Object>) data.get("acceptance"), "api").get(3).put("method", "PATCH");
        });
        located(rule, first, "api itemStats GET /api/items/stats", second, "api itemPUT PUT /api/items/:id");
        assertTrue(ContractPlanner.schemaFeedback(first).contains("; tested GET paths /api/items/:fixture; add one 2xx READ"));
        var both = ContractPlanner.schemaFeedback(reject(ContractFixtures.mern(), data -> {
            endpoint(data, "itemStats", "GET", "/api/items/stats"); endpoint(data, "itemTags", "GET", "/api/items/tags"); }));
        assertTrue(both.contains("api itemStats GET /api/items/stats, api itemTags GET /api/items/tags have no 2xx"), both);
        // Coordinator auth requests are never offered as the tested paths of a data endpoint.
        var accounts = reject(ContractFixtures.accounts(), data -> {
            endpoint(data, "itemSearch", "POST", "/api/items/search"); list(data, "api").getLast().put("authenticated", true); });
        assertEquals(rule, ContractPlanner.rejectionSignature(accounts));
        String auth = ContractPlanner.schemaFeedback(accounts);
        assertTrue(auth.contains("api itemSearch POST /api/items/search") && auth.contains("tested POST paths /api/items;"), auth);
        assertFalse(auth.contains("/api/auth/"), auth);
        var many = reject(ContractFixtures.mern(), data -> {
            for (int i = 0; i < 6; i++) endpoint(data, "itemReportWithLongName" + i, "GET", "/api/items/reports/monthly/summary/" + i); });
        assertEquals(rule, ContractPlanner.rejectionSignature(many));
        String more = ContractPlanner.schemaFeedback(many);
        assertTrue(more.contains("api itemReportWithLongName0 GET") && more.contains(" more have no 2xx"), more);
    }

    @Test void unboundedOrUnsafeDetailFallsBackToTheRuleOnly() {
        assertEquals("api: router ownership mismatch", new ContractRuleException("api: router ownership mismatch", "owner <script>\n").getMessage());
        assertEquals("units: duplicate unit id", new ContractRuleException("units: duplicate unit id", "x".repeat(601)).getMessage());
    }
}
