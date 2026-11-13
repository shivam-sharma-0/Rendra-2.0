package com.code.pal.agent.contract;

import com.code.pal.agent.validation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AccountAcceptanceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> proposal() {
        var data = mapper.readValue(mapper.writeValueAsString(ContractFixtures.accounts()), Map.class);
        var acceptance = (Map<String, Object>) data.get("acceptance");
        var api = (List<Map<String, Object>>) acceptance.get("api");
        acceptance.put("api", new ArrayList<>(api.stream().filter(step -> !step.get("kind").toString().startsWith("AUTH_")).toList()));
        ((List<Map<String, Object>>) data.get("units")).forEach(unit -> unit.put("acceptanceIds", List.of()));
        return data;
    }

    @Test void missingAuthIsCompiledWithoutModelCallsAndEveryCrudAssertionRemains() {
        var data = proposal();
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data), mapper), BuildContract.class);
        var original = ContractFixtures.accounts().acceptance().api().stream().filter(step -> !step.kind().name().startsWith("AUTH_")).toList();
        var checks = compiled.acceptance().api();
        assertEquals(original.size() + 5, checks.size());
        assertEquals(List.of(BehaviorCriteria.ApiKind.HEALTH, BehaviorCriteria.ApiKind.AUTH_SIGNUP, BehaviorCriteria.ApiKind.AUTH_LOGIN),
                checks.subList(0, 3).stream().map(BehaviorCriteria.ApiStep::kind).toList());
        for (var step : original) {
            var actual = checks.stream().filter(check -> check.id().equals(step.id())).findFirst().orElseThrow();
            assertEquals(step.path(), actual.path());
            assertEquals(step.status(), actual.status());
            assertEquals(step.assertPointer(), actual.assertPointer());
            assertEquals(step.assertValue(), actual.assertValue());
            if (step.kind() != BehaviorCriteria.ApiKind.HEALTH) assertEquals("platformLoginToken", actual.tokenVariable());
        }
        assertEquals(compiled, mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(compiled), mapper), BuildContract.class));
        assertTrue(AccountAcceptance.browserSession(compiled.acceptance(), UUID.randomUUID()).containsKey("storageKey"));
    }

    @SuppressWarnings("unchecked")
    @Test void anExistingSignupStaysBeforeAnInsertedLogin() {
        var data = proposal();
        var raw = (Map<String, Object>) data.get("acceptance");
        var api = (List<Map<String, Object>>) raw.get("api");
        api.add(1, mapper.readValue(mapper.writeValueAsString(ContractFixtures.accounts().acceptance().api().get(1)), Map.class));
        assertTrue(AccountAcceptance.normalize(data, mapper));
        var criteria = mapper.convertValue(raw, BehaviorCriteria.class);
        assertEquals(BehaviorCriteria.ApiKind.AUTH_SIGNUP, criteria.api().get(1).kind());
        assertEquals(BehaviorCriteria.ApiKind.AUTH_LOGIN, criteria.api().get(2).kind());
    }

    @Test void aPlannerProposalMissingAuthSetupUsesOneModelCall() {
        var original = ContractFixtures.accounts();
        var input = new ContractPlanner.Input(original.id(), "Build tasks", "Approved", "Map",
                new com.code.pal.project.capability.BuildDecisions.Choice(1, original.storage(), original.auth(), null), original.limits());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = new ContractPlanner(mapper).plan(input, new com.code.pal.agent.config.AgentProperties.ModelSelection("fake", "fixture"), request -> {
            calls.incrementAndGet();
            return new com.code.pal.agent.llm.LlmClient.LlmResult(mapper.writeValueAsString(proposal()), null, List.of(), null, "stop");
        });
        assertEquals(1, calls.get());
        assertTrue(result.acceptance().api().stream().anyMatch(step -> step.kind() == BehaviorCriteria.ApiKind.AUTH_LOGIN));
    }

    @Test void existingCompleteAuthRemainsUnchangedAndNonPlatformAuthIsNotInvented() {
        var complete = mapper.readValue(mapper.writeValueAsString(ContractFixtures.accounts()), Map.class);
        String before = mapper.writeValueAsString(complete);
        assertFalse(AccountAcceptance.normalize(complete, mapper));
        assertEquals(before, mapper.writeValueAsString(complete));
        var unsupported = proposal(); unsupported.put("stack", "VITE");
        assertFalse(AccountAcceptance.normalize(unsupported, mapper));
        assertThrows(RuntimeException.class, () -> mapper.convertValue(unsupported.get("acceptance"), BehaviorCriteria.class));
    }

    @SuppressWarnings("unchecked")
    @Test void captureCollisionsAndRequiredCheckOverflowStayRejected() {
        var data = proposal();
        var raw = (Map<String, Object>) data.get("acceptance");
        var api = (List<Map<String, Object>>) raw.get("api");
        api.get(1).put("captureVariable", "platformLoginToken");
        var collision = data;
        assertThrows(IllegalArgumentException.class, () -> AccountAcceptance.normalize(collision, mapper));
        data = proposal(); raw = (Map<String, Object>) data.get("acceptance");
        api = (List<Map<String, Object>>) raw.get("api");
        while (api.size() < BehaviorCriteria.API_CAP) { var step = new LinkedHashMap<>(api.getLast()); step.put("id", "extra_" + api.size()); api.add(step); }
        assertTrue(AccountAcceptance.normalize(data, mapper));
        var acceptance = raw;
        assertThrows(RuntimeException.class, () -> mapper.convertValue(acceptance, BehaviorCriteria.class));
    }

    @Test void explicitUiAuthKeepsItsDeclaredSelectorsRatherThanSkippingTheForms() {
        var base = ContractFixtures.accounts().acceptance();
        var criteria = new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction("password", BehaviorCriteria.BrowserKind.FILL,
                "input[name='password']", "test value")), base.api(), true, true);
        assertTrue(AccountAcceptance.browserSession(criteria, UUID.randomUUID()).isEmpty());
    }
}
