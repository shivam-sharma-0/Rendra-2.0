package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlannedBatchTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void singlePlannedUnitUsesOneSliceCallWithoutTheFullContractOrRequirements() {
        var spec = SpecFixtures.approved();
        var budget = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);
        var layout = new DeterministicPlan(mapper).plan(spec, budget).orElseThrow();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec, layout, ContractFixtures.limits());
        var batch = contract.batches().getFirst();
        var unit = contract.units().getFirst();
        var inputs = List.of(new UnitGenerator.Input(contract, unit, "Whole unrelated request", "Private unrelated requirements",
                Map.of("index.html", new UnitGenerator.FileView("absent", null),
                        "styles.css", new UnitGenerator.FileView("absent", null),
                        "unrelated.txt", new UnitGenerator.FileView("sha256:other", "UNRELATED_BODY")), ""));
        var count = new AtomicInteger();
        var generated = new BatchUnitGenerator(mapper).generatePlanned(contract, batch, inputs, Set.of(),
                new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    count.incrementAndGet();
                    var text = request.messages().get(1).content();
                    assertFalse(text.contains("UNRELATED_BODY"));
                    assertFalse(text.contains("Private unrelated requirements"));
                    assertFalse(mapper.readTree(text).has("contract"));
                    assertTrue(request.maxTokens() <= 6_000);
                    var output = new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput(unit.id(),
                            unit.ownedPaths().stream().map(path -> new UnitGenerator.Delta(path, "absent", "generated")).toList())));
                    return new LlmClient.LlmResult(mapper.writeValueAsString(output), null, List.of(), null, "stop");
                });
        assertEquals(1, count.get());
        assertEquals(2, generated.getFirst().files().size());
    }

    @Test
    void inventedCompletionCannotBypassARealBatchDependency() {
        var fixture = new SpecPlanningTest();
        var contract = fixture.deterministic(SpecFixtures.approved());
        var original = contract.batches().getFirst();
        var foreign = new ContractBatch(original.id(), original.unitIds(), List.of("inventedBatch"), original.ownedPaths(),
                original.interfaces(), original.acceptanceIds(), original.inputCap(), original.outputCap(), original.estimatedOutputTokens());
        var input = new UnitGenerator.Input(contract, contract.units().getFirst(), "request", "requirements",
                Map.of("index.html", new UnitGenerator.FileView("absent", null), "styles.css", new UnitGenerator.FileView("absent", null)), "");
        assertThrows(IllegalArgumentException.class, () -> new BatchUnitGenerator(mapper).generatePlanned(
                contract, foreign, List.of(input), Set.of("inventedBatch"), new AgentProperties.ModelSelection("mistral", "synthetic"),
                request -> { fail("No call for an unbound batch"); return null; }));
    }

    @Test
    void dependentBatchesRunInTopologicalOrderAndDoNotLeakUnrelatedBodies() {
        var base = BuildContractTest.valid(List.of(BuildContractTest.unit("page", "index.html", List.of("data")),
                BuildContractTest.unit("data", "data.js", List.of())));
        var page = new ContractBatch("pageBatch", List.of("page"), List.of("dataBatch"), List.of("index.html"),
                List.of(), List.of("result"), 32_000, 6_000, 100);
        var data = new ContractBatch("dataBatch", List.of("data"), List.of(), List.of("data.js"),
                List.of(), List.of("result"), 32_000, 6_000, 100);
        var contract = ContractPlans.bind(base, SpecFixtures.approved(), List.of(page, data));
        assertEquals(List.of("dataBatch", "pageBatch"), ContractPlans.ordered(contract.batches()).stream().map(ContractBatch::id).toList());
        var input = new UnitGenerator.Input(contract, contract.units().getFirst(), "request", "requirements",
                Map.of("index.html", new UnitGenerator.FileView("absent", null),
                        "data.js", new UnitGenerator.FileView("sha256:data", "UNRELATED_BODY")), "");
        var generator = new BatchUnitGenerator(mapper);
        var model = new AgentProperties.ModelSelection("mistral", "synthetic");
        assertThrows(IllegalArgumentException.class, () -> generator.generatePlanned(contract, page, List.of(input), Set.of(), model,
                request -> { fail("A real dependency is not complete"); return null; }));
        generator.generatePlanned(contract, page, List.of(input), Set.of("dataBatch"), model, request -> {
            assertFalse(request.messages().get(1).content().contains("UNRELATED_BODY"));
            return reply(new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput("page",
                    List.of(new UnitGenerator.Delta("index.html", "absent", "<h1>Complete</h1>"))))));
        });
    }

    @Test
    void malformedOrTruncatedBatchGetsOnlyOneCorrectionAndNoPartialOutput() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var batch = contract.batches().getFirst();
        var input = new UnitGenerator.Input(contract, contract.units().getFirst(), "request", "requirements",
                Map.of("index.html", new UnitGenerator.FileView("absent", null), "styles.css", new UnitGenerator.FileView("absent", null)), "");
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new BatchUnitGenerator(mapper).generatePlanned(contract, batch,
                List.of(input), Set.of(), new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    calls.incrementAndGet();
                    return new LlmClient.LlmResult("{\"units\":[", null, List.of(), null, "length");
                }));
        assertEquals(2, calls.get());
    }

    @Test
    void serverSealsSharedHashesAndRejectsImportsWithoutTrustedDefinitions() {
        var original = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var unit = original.units().getFirst();
        String path = original.clientRoot() + "theme.css";
        var imported = new BuildContract.Unit(unit.id(), unit.objective(), unit.ownedPaths(), List.of(path), unit.dependsOn(), unit.acceptanceIds());
        var base = new BuildContract(1, original.id(), original.stack(), original.storage(), original.auth(), original.limits(),
                original.theme(), original.routes(), original.types(), original.models(), original.api(), original.assets(),
                original.envNames(), List.of(imported), original.acceptance());
        var contract = ContractPlans.bind(base, SpecFixtures.approved(), original.batches());
        assertThrows(IllegalArgumentException.class, () -> ContractPlans.sealInterfaces(contract, Map.of()));
        var sealed = ContractPlans.sealInterfaces(contract, Map.of(path, ":root { color: black }"));
        assertTrue(contract.batches().getFirst().interfaces().isEmpty());
        assertEquals("sha256:" + ContractStore.hash(":root { color: black }"), sealed.batches().getFirst().interfaces().getFirst().hash());
    }

    private LlmClient.LlmResult reply(Object output) {
        return new LlmClient.LlmResult(mapper.writeValueAsString(output), null, List.of(), null, "stop");
    }

    @Test
    void completeButOversizedSourceNeverReturnsAPartialBatch() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var batch = contract.batches().getFirst();
        var unit = contract.units().getFirst();
        var input = new UnitGenerator.Input(contract, unit, "request", "requirements",
                Map.of("index.html", new UnitGenerator.FileView("absent", null), "styles.css", new UnitGenerator.FileView("absent", null)), "");
        var calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new BatchUnitGenerator(mapper).generatePlanned(contract, batch,
                List.of(input), Set.of(), new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    calls.incrementAndGet();
                    return reply(new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput(unit.id(),
                            List.of(new UnitGenerator.Delta("index.html", "absent", "x".repeat(6_001)),
                                    new UnitGenerator.Delta("styles.css", "absent", ""))))));
                }));
        assertEquals(2, calls.get());
    }

    @Test
    void copiedAssetOnlyBatchNeedsNoModelAndNeverRegeneratesTheAsset() {
        var base = ContractFixtures.staticSite();
        var unit = new BuildContract.Unit("image", "Copy approved image", List.of("image.png"), List.of(), List.of(), List.of("result"));
        var source = new BuildContract(1, base.id(), base.stack(), base.storage(), base.auth(), base.limits(), base.theme(),
                base.routes(), base.types(), base.models(), base.api(),
                List.of(new BuildContract.Asset("assets/input.png", "image.png", SpecFixtures.HASH, "image")), base.envNames(), List.of(unit), base.acceptance());
        var batch = new ContractBatch("assets", List.of("image"), List.of(), List.of("image.png"), List.of(), List.of("result"), 32_000, 6_000, 1);
        var contract = ContractPlans.bind(source, SpecFixtures.approved(), List.of(batch));
        var input = new UnitGenerator.Input(contract, unit, "request", "requirements", Map.of(), "");
        var output = new BatchUnitGenerator(mapper).generatePlanned(contract, batch, List.of(input), Set.of(),
                new AgentProperties.ModelSelection("mistral", "synthetic"), request -> { fail("Copied assets never need generation"); return null; });
        assertEquals(List.of(new UnitGenerator.Output(List.of())), output);
    }
}
