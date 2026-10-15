package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SpecPlanningTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BatchBudget.Limits budget = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);
    private final AgentProperties.ModelSelection model = new AgentProperties.ModelSelection("mistral", "synthetic");

    BuildContract deterministic(ApprovedSpec spec) {
        var layout = new DeterministicPlan(mapper).plan(spec, budget).orElseThrow();
        return ContractPlans.fromLayout(new UUID(10, 20), spec, layout, ContractFixtures.limits());
    }

    ContractPlanner.SpecInput input(ApprovedSpec spec, UUID id) {
        var choice = new BuildDecisions.Choice(1, BuildDecisions.Storage.valueOf(spec.desired().storage().name()),
                BuildDecisions.Auth.valueOf(spec.desired().auth().name()), null);
        return new ContractPlanner.SpecInput(new ContractPlanner.Input(id, "Build the approved spec",
                mapper.writeValueAsString(spec), "Small map", choice, ContractFixtures.limits()), spec, budget);
    }

    @Test
    void v2RoundTripsAndLegacySerializationKeepsItsOldFields() {
        var contract = deterministic(SpecFixtures.approved());
        assertEquals(2, contract.schemaVersion());
        assertEquals(SpecFixtures.approved().specId(), contract.specId());
        assertEquals(contract, mapper.readValue(mapper.writeValueAsString(contract), BuildContract.class));
        var legacy = mapper.readTree(mapper.writeValueAsString(ContractFixtures.staticSite()));
        assertFalse(legacy.has("specId"));
        assertFalse(legacy.has("batches"));
    }

    @Test
    void staticAndViteSpecPlansUseOneCallAndTheLeanUnitsGrammar() {
        for (var spec : List.of(SpecFixtures.approved(), SpecFixtures.browser())) {
            var contract = deterministic(spec);
            var calls = new AtomicInteger();
            var actual = new ContractPlanner(mapper).plan(input(spec, contract.id()), model, request -> {
                calls.incrementAndGet();
                assertEquals(CallPurpose.PLANNER, LlmCallContext.current().purpose());
                var schema = mapper.readTree(request.jsonSchema().schema());
                assertEquals(List.of("units"), mapper.convertValue(schema.path("required"), List.class));
                assertEquals(1, schema.path("properties").size());
                assertTrue(request.tools().isEmpty());
                return reply(mapper.writeValueAsString(contract));
            }, ignored -> {});
            assertEquals(contract, actual);
            assertEquals(1, calls.get());
        }
    }

    @Test
    void invalidPlannerOutputGetsOneCorrectionAndTransportFailuresDoNotBecomeCorrections() {
        var spec = SpecFixtures.approved();
        var contract = deterministic(spec);
        var count = new AtomicInteger();
        var planner = new ContractPlanner(mapper);
        assertEquals(contract, planner.plan(input(spec, contract.id()), model, request -> {
            int attempt = count.incrementAndGet();
            assertEquals(attempt == 1 ? CallPurpose.PLANNER : CallPurpose.SCHEMA_CORRECTION,
                    LlmCallContext.current().purpose());
            return reply(attempt == 1 ? "{}" : mapper.writeValueAsString(contract));
        }, ignored -> {}));
        assertEquals(2, count.get());
        count.set(0);
        assertThrows(IllegalArgumentException.class, () -> planner.plan(input(spec, contract.id()), model,
                request -> { count.incrementAndGet(); return reply("{}"); }, ignored -> {}));
        assertEquals(2, count.get());
        count.set(0);
        assertThrows(LlmCancelledException.class, () -> planner.plan(input(spec, contract.id()), model,
                request -> { count.incrementAndGet(); throw new LlmCancelledException(); }, ignored -> {}));
        assertEquals(1, count.get());
    }

    @Test
    void supabaseCapabilitiesAreCompletedByCodeAndAreNotModelWrittenFields() {
        var plain = SpecFixtures.approved();
        var spec = SpecFixtures.envelope(plain.spec(), new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.SUPABASE, ApprovedSpec.Auth.NONE), ApprovedSpec.Readiness.READY);
        var actual = new ContractPlanner(mapper).plan(input(spec, new UUID(10, 20)), model,
                request -> reply("{\"units\":[{\"ownedPaths\":[\"src/App.tsx\"]}]}"), ignored -> {});
        assertEquals(BuildContract.Stack.VITE, actual.stack());
        assertEquals(BuildDecisions.Storage.SUPABASE, actual.storage());
        assertEquals(BuildDecisions.Auth.NONE, actual.auth());
        assertEquals(Set.of("VITE_SUPABASE_URL", "VITE_SUPABASE_ANON_KEY"), actual.envNames());
    }

    @Test
    void oversizedIndependentBatchGetsAnImmutableVersionedReplacement() {
        var base = ContractFixtures.staticSite();
        var units = new ArrayList<BuildContract.Unit>();
        for (int index = 0; index < 3; index++) {
            units.add(new BuildContract.Unit("page" + index, "Page " + index, List.of("page" + index + ".html"),
                    List.of(), List.of(), List.of("result")));
        }
        base = new BuildContract(1, base.id(), base.stack(), base.storage(), base.auth(), base.limits(),
                base.theme(), List.of(), base.types(), base.models(), base.api(), base.assets(), Set.of(), units, base.acceptance());
        var batch = new ContractBatch("all", units.stream().map(BuildContract.Unit::id).toList(), List.of(),
                units.stream().flatMap(unit -> unit.ownedPaths().stream()).toList(), List.of(), List.of("result"),
                32_000, 6_000, 1);
        var original = ContractPlans.bind(base, SpecFixtures.approved(), List.of(batch));
        var replacement = ContractPlans.splitOversized(original, budget, mapper);
        assertNotEquals(original.id(), replacement.id());
        assertEquals(original.id(), replacement.replacesContractId());
        assertEquals(original.planRevision() + 1, replacement.planRevision().longValue());
        assertEquals(1, original.batches().size());
        assertTrue(replacement.batches().size() > 1);
        assertEquals(original.ownership(), replacement.ownership());
        assertEquals(original.acceptance(), replacement.acceptance());
        assertEquals(replacement, ContractPlans.splitOversized(replacement, budget, mapper));
    }

    @Test
    void batchesCannotOmitOwnershipOrBreakTopologicalDependencies() {
        var contract = deterministic(SpecFixtures.approved());
        var foreign = new ContractBatch("app", List.of("app"), List.of(), List.of("foreign.ts"), List.of(),
                contract.batches().getFirst().acceptanceIds(), 32_000, 6_000, 100);
        assertThrows(IllegalArgumentException.class,
                () -> ContractPlans.bind(contract, SpecFixtures.approved(), List.of(foreign)));
    }

    @Test
    void sourceAuthorityFailurePropagatesWithoutAPlannerCorrection() {
        var spec = SpecFixtures.approved();
        var contract = deterministic(spec);
        var calls = new AtomicInteger();
        var denied = new IllegalStateException("Lease lost");
        assertSame(denied, assertThrows(IllegalStateException.class, () -> new ContractPlanner(mapper).plan(
                input(spec, contract.id()), model, request -> {
                    calls.incrementAndGet();
                    return reply(mapper.writeValueAsString(contract));
                }, proposal -> { throw denied; })));
        assertEquals(1, calls.get());
    }

    @Test
    void foreignSpecOrWeakenedAcceptanceCannotSurviveTheCorrectionLimit() {
        var spec = SpecFixtures.approved();
        var contract = deterministic(spec);
        String json = mapper.writeValueAsString(contract).replace("Solar Bakery", "Anything");
        var count = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input(spec, contract.id()), model,
                request -> { count.incrementAndGet(); return reply(json); }, ignored -> fail("No invalid contract persisted")));
        assertEquals(2, count.get());
    }

    @Test
    void loweredBatchOutputCapCannotBeRaisedByRepartitioning() {
        var contract = deterministic(SpecFixtures.approved());
        var original = contract.batches().getFirst();
        var small = new ContractBatch(original.id(), original.unitIds(), original.dependsOn(), original.ownedPaths(),
                original.interfaces(), original.acceptanceIds(), original.inputCap(), 1_500, 100);
        var bounded = ContractPlans.bind(contract, SpecFixtures.approved(), List.of(small));
        assertThrows(IllegalArgumentException.class, () -> ContractPlans.splitOversized(bounded, budget, mapper));
        assertEquals(1_500, bounded.batches().getFirst().outputCap());
    }

    private LlmClient.LlmResult reply(String content) {
        return new LlmClient.LlmResult(content, null, List.of(), new LlmClient.Usage(10, 20), "stop");
    }
}
