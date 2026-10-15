package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class UnstoredUnitSplitTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SpecPlanningTest fixture = new SpecPlanningTest();

    @Test
    void oversizedAppUnitIsPartitionedByFileInOnePlannerCallWithAValidDependencyGraph() {
        var original = SpecFixtures.approved();
        var spec = SpecFixtures.envelope(original.spec(), new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), original.readiness());
        var input = fixture.input(spec, fixture.deterministic(original).id());
        String draft = """
                {"units":[{"id":"app","objective":"Build the approved bakery",
                "ownedPaths":["src/App.tsx","src/One.tsx","src/Two.tsx","src/state.ts","src/index.css"]}]}
                """;
        var calls = new AtomicInteger();
        var actual = new ContractPlanner(mapper).plan(input, new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
            calls.incrementAndGet();
            return new LlmClient.LlmResult(draft, null, List.of(), null, "stop");
        }, ignored -> {});
        assertEquals(1, calls.get());
        assertEquals(Set.of("src/App.tsx", "src/One.tsx", "src/Two.tsx", "src/state.ts", "src/index.css"), actual.ownership().keySet());
        assertTrue(actual.units().size() > 1);
        assertTrue(actual.batches().size() > 1);
        assertEquals(actual, ContractPlans.splitOversized(actual, input.budget(), mapper));
        assertEquals("Solar Bakery", actual.acceptance().browser().getFirst().value());
        var app = actual.units().stream().filter(unit -> unit.ownedPaths().contains("src/App.tsx")).findFirst().orElseThrow();
        assertTrue(app.imports().containsAll(List.of("src/One.tsx", "src/Two.tsx", "src/state.ts", "src/index.css")));
        assertTrue(actual.waves().size() >= 2);
        assertEquals(actual.units().size(), actual.waves().stream().mapToInt(List::size).sum());
    }

    @Test
    void deterministicSplitPreservesRoutesAndCannotMutateAnAlreadyBoundContract() {
        var original = fixture.deterministic(SpecFixtures.approved());
        var split = ContractPlans.splitOversized(original, new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true), mapper);
        assertSame(original, split);
        assertEquals(original.routes(), split.routes());
        var before = mapper.writeValueAsString(original);
        assertThrows(IllegalArgumentException.class, () -> UnstoredUnitSplit.split(original,
                new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true), mapper));
        assertEquals(before, mapper.writeValueAsString(original));
    }

    @Test
    void threePageViteDraftSplitsOnceWithoutInventingSiblingPageDependencies() {
        var original = SpecFixtures.approved();
        var pages = List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content")),
                new ApprovedSpec.Page("about", "/about", "About", List.of("content")),
                new ApprovedSpec.Page("classes", "/classes", "Classes", List.of("content")));
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Bakery pages", List.of("home", "about", "classes"), List.of());
        var body = SpecFixtures.with(original, pages, List.of(), List.of(feature), original.spec().acceptance()).spec();
        var spec = SpecFixtures.envelope(body, new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), ApprovedSpec.Readiness.READY);
        var input = fixture.input(spec, fixture.deterministic(original).id());
        var calls = new AtomicInteger();
        var actual = new ContractPlanner(mapper).plan(input, new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
            calls.incrementAndGet();
            return new LlmClient.LlmResult("{\"units\":[{\"id\":\"app\",\"files\":[\"src/pages/home.tsx\"]}]}", null, List.of(), null, "stop");
        }, ignored -> {});
        assertEquals(1, calls.get());
        assertEquals(Set.of("/", "/about", "/classes"), actual.routes().stream().map(BuildContract.Route::path).collect(Collectors.toSet()));
        for (var route : actual.routes()) {
            assertEquals(route.owner(), actual.ownership().get(route.componentPath()));
            var owner = actual.units().stream().filter(unit -> unit.id().equals(route.owner())).findFirst().orElseThrow();
            assertTrue(owner.imports().contains("src/index.css"));
            assertTrue(actual.routes().stream().noneMatch(other -> owner.imports().contains(other.componentPath())));
        }
        assertTrue(actual.batches().stream().allMatch(batch -> ContractPlans.estimate(actual, batch.unitIds(), input.budget(), new BatchBudget(mapper)).fits()));
        SpecContractPlanner.verifyCoverage(spec, actual);
    }

    @Test
    void indivisibleFileStillUsesOnlyOneCorrectionAndNeverRaisesOutputCapacity() {
        var original = SpecFixtures.approved();
        var spec = SpecFixtures.envelope(original.spec(), new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), original.readiness());
        var base = fixture.input(spec, fixture.deterministic(original).id());
        var input = new ContractPlanner.SpecInput(base.base(), spec, new BatchBudget.Limits(32_000, 2_000, 32_000, 128, true));
        var calls = new AtomicInteger();
        var failure = assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input,
                new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    if (calls.incrementAndGet() == 2) {
                        assertTrue(request.messages().getLast().content().contains("An individual source file exceeds the fixed unit budget"));
                    }
                    return new LlmClient.LlmResult("{\"units\":[{\"files\":[\"src/App.tsx\"]}]}", null, List.of(), null, "stop");
                }, ignored -> fail("No oversized draft may be stored")));
        assertEquals(2, calls.get());
        assertTrue(failure.getMessage().contains("An individual source file exceeds the fixed unit budget"));
    }

    @Test
    void splitHelperBatchKeepsItsApprovedFeaturesAndEntityContext() {
        var spec = SpecFixtures.browser();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        var contract = new ContractPlanner(mapper).plan(input, new AgentProperties.ModelSelection("mistral", "synthetic"), request ->
                new LlmClient.LlmResult("{\"units\":[{\"files\":[\"src/App.tsx\",\"src/Panel.tsx\",\"src/local-state.ts\"]}]}",
                        null, List.of(), null, "stop"), ignored -> {});
        var batch = contract.batches().stream().filter(candidate -> candidate.unitIds().stream()
                .noneMatch(id -> contract.routes().stream().anyMatch(route -> route.owner().equals(id)))).findFirst().orElseThrow();
        var snapshot = new TreeMap<String, UnitGenerator.FileView>();
        contract.units().forEach(unit -> unit.ownedPaths().forEach(path -> snapshot.put(path, new UnitGenerator.FileView("absent", null))));
        var units = contract.units().stream().filter(unit -> batch.unitIds().contains(unit.id()))
                .map(unit -> new UnitGenerator.Input(contract, unit, "Build tasks", "Approved", snapshot, "")).toList();
        var marker = new IllegalStateException("Stop after inspecting the synthetic request");
        assertSame(marker, assertThrows(IllegalStateException.class, () -> new BatchUnitGenerator(mapper).generatePlanned(contract,
                batch, units, Set.copyOf(batch.dependsOn()), spec, new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    var data = mapper.readTree(request.messages().get(1).content());
                    assertEquals("tasks", data.at("/spec/features/0/id").asString());
                    assertEquals("task", data.at("/spec/entities/0/id").asString());
                    assertEquals("home", data.at("/spec/pages/0/id").asString());
                    throw marker;
                })));
    }
}
