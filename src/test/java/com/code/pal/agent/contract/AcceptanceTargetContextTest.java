package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AcceptanceTargetContextTest {
    @Test
    void generatorGetsTheStableTargetManifestAndTheInstructionToRenderRealSemanticElements() {
        var mapper = new ObjectMapper();
        var spec = SpecFixtures.approved();
        var contract = new SpecPlanningTest().deterministic(spec);
        var input = new UnitGenerator.Input(contract, contract.units().getFirst(), "Build bakery", "Approved", Map.of(
                "index.html", new UnitGenerator.FileView("absent", null), "styles.css", new UnitGenerator.FileView("absent", null)), "");
        var marker = new IllegalStateException("Inspected synthetic request");
        assertSame(marker, assertThrows(IllegalStateException.class, () -> new BatchUnitGenerator(mapper).generatePlanned(contract,
                contract.batches().getFirst(), List.of(input), Set.of(), spec, new AgentProperties.ModelSelection("mistral", "synthetic"), request -> {
                    var data = mapper.readTree(request.messages().get(1).content());
                    assertEquals("h1", data.at("/acceptanceTargets/0/originalSelector").asString());
                    var testId = data.at("/acceptanceTargets/0/testId").asString();
                    assertTrue(contract.acceptance().browser().getFirst().selector().contains(testId));
                    assertTrue(request.messages().getFirst().content().contains("real semantic elements"));
                    throw marker;
                })));
    }

    @Test
    void historicalReviewedSelectorsStayReadableButNewProposalsMustUseCompiledAssertions() {
        var spec = SpecFixtures.approved();
        var original = new SpecPlanningTest().deterministic(spec);
        var raw = new BehaviorCriteria(1, List.of(new BrowserAction("heading_0", BrowserKind.ASSERT_TEXT, "h1", "Solar Bakery")), List.of(), false, false);
        var historical = withAcceptance(original, raw);
        var mapper = new ObjectMapper();
        assertEquals(historical, mapper.readValue(mapper.writeValueAsString(historical), BuildContract.class));
        assertThrows(IllegalArgumentException.class, () -> SpecContractPlanner.verifyCoverage(spec, historical));
        assertTrue(SpecAcceptance.targets(historical, spec, Set.of("heading_0")).isEmpty());
        var weak = new BehaviorCriteria(1, List.of(new BrowserAction("heading_0", BrowserKind.ASSERT_TEXT,
                original.acceptance().browser().getFirst().selector(), "Different heading")), List.of(), false, false);
        assertThrows(IllegalArgumentException.class, () -> SpecContractPlanner.verifyCoverage(spec, withAcceptance(original, weak)));
    }

    private BuildContract withAcceptance(BuildContract c, BehaviorCriteria acceptance) {
        return new BuildContract(c.schemaVersion(), c.id(), c.stack(), c.storage(), c.auth(), c.limits(), c.theme(), c.routes(),
                c.types(), c.models(), c.api(), c.assets(), c.envNames(), c.units(), acceptance, c.specId(), c.specRevision(),
                c.specHash(), c.planRevision(), c.replacesContractId(), c.templateVersion(), c.batches());
    }
}
