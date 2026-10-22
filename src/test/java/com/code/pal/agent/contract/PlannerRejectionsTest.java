package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.validation.AcceptanceStepException;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class PlannerRejectionsTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AgentProperties properties = new AgentProperties();
    final BuildContract contract = ContractFixtures.mern();
    final ContractPlanner.Input input = new ContractPlanner.Input(contract.id(), "Items", "Approved", "Map",
            new BuildDecisions.Choice(1, contract.storage(), contract.auth(), null), contract.limits());

    PlannerRejectionsTest() {
        properties.setBillingMode(AgentProperties.BillingMode.FREE);
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getModels().setFreePlan(List.of("mistral:codestral-latest", "nvidia:alternate", "openrouter:third"));
        properties.getPlanning().setMaxRouteAttempts(3);
    }
    LlmClient.LlmResult reply(String content) { return new LlmClient.LlmResult(content, null, List.of(), new LlmClient.Usage(3, 2), "stop"); }
    String badDependency(String unit, String dependency, int revision) {
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsString(contract), Map.class);
        var units = (List<Map<String, Object>>) data.get("units");
        units.stream().filter(value -> unit.equals(value.get("id"))).findFirst().orElseThrow().put("dependsOn", List.of(dependency));
        units.getFirst().put("objective", "Changed prose " + revision);
        return mapper.writeValueAsString(data);
    }

    @Test void changedPayloadWithTheSameRejectionRotatesExactlyOnceWithDistinctAccounting() {
        var routes = new ArrayList<String>(); var ids = new HashSet<UUID>();
        var guarded = new ModelOutputProgress(properties, mapper, ignored -> fail("Planning uses rejection signatures"));
        var result = new ContractPlanner(mapper, properties).plan(input, properties.planningRoutes().getFirst(), request ->
                guarded.call(request, physical -> {
                    routes.add(physical.model()); assertTrue(ids.add(LlmLogicalCall.currentOrNew()));
                    assertTrue(physical.budget().toMillis() > 0);
                    if (routes.size() == 2) assertTrue(physical.messages().getLast().content().contains("dependsOn/ui: unknown unit"));
                    return reply(routes.size() == 3 ? mapper.writeValueAsString(contract) : badDependency("ui", "feature_" + routes.size(), routes.size()));
                }));
        assertEquals(contract, result);
        assertEquals(List.of("codestral-latest", "codestral-latest", "alternate"), routes);
    }

    @Test void repeatedRejectionOnTheAlternateStopsWithoutCallingTheThirdRoute() {
        var routes = new ArrayList<String>();
        var failure = assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper, properties)
                .plan(input, properties.planningRoutes().getFirst(), request -> {
                    routes.add(request.model()); return reply(badDependency("ui", "different_" + routes.size(), routes.size()));
                }));
        assertEquals(List.of("codestral-latest", "codestral-latest", "alternate"), routes);
        assertTrue(failure.getMessage().contains("dependsOn/ui: unknown unit"));
        assertTrue(failure.getMessage().contains("wastedCalls=2"));
    }

    @Test void aDifferentRejectionLocationDoesNotGetAnExtraCorrection() {
        var routes = new ArrayList<String>();
        var failure = assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper, properties)
                .plan(input, properties.planningRoutes().getFirst(), request -> {
                    routes.add(request.model()); return reply(badDependency(routes.size() == 1 ? "ui" : "data", "missing", routes.size()));
                }));
        assertEquals(2, routes.size());
        assertTrue(failure.getMessage().contains("dependsOn/data: unknown unit"));
        assertFalse(failure.getMessage().contains("no progress"));
    }

    @Test void cancellationOnTheAlternatePropagatesWithoutAnotherCorrection() {
        var routes = new ArrayList<String>();
        assertThrows(LlmCancelledException.class, () -> new ContractPlanner(mapper, properties)
                .plan(input, properties.planningRoutes().getFirst(), request -> {
                    routes.add(request.model()); if (routes.size() == 3) throw new LlmCancelledException();
                    return reply(badDependency("ui", "missing", routes.size()));
                }));
        assertEquals(3, routes.size());
    }

    @Test void operatorRouteBoundNeverAddsAnUnconfiguredCorrection() {
        properties.getPlanning().setMaxRouteAttempts(1);
        var routes = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper, properties)
                .plan(input, properties.planningRoutes().getFirst(), request -> {
                    routes.add(request.model()); return reply(badDependency("ui", "missing", routes.size()));
                }));
        assertEquals(List.of("codestral-latest", "codestral-latest"), routes);
    }

    @Test void acceptanceRuleSignatureIgnoresTheRejectedValueButKeepsTheLocation() {
        var first = new AcceptanceStepException("browser", 1, "route", BrowserKind.ASSERT_ROUTE, "value", null, "local route", null);
        var second = new AcceptanceStepException("browser", 1, "route", BrowserKind.ASSERT_ROUTE, "value", "invalid", "local route", null);
        assertEquals(ContractPlanner.rejectionSignature(first), ContractPlanner.rejectionSignature(second));
    }
}
