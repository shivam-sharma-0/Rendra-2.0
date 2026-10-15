package com.code.pal.agent.contract;

import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractScaffoldV2Test {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContractScaffold scaffold = new ContractScaffold(mapper);

    @Test
    void newViteSeparatesTypecheckFromBuildAndPreservesAnExistingManifest() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        var output = scaffold.render(contract, "Tasks", path -> null);
        var scripts = mapper.readTree(output.get("package.json")).path("scripts");
        assertEquals("vite build", scripts.path("build").asString());
        assertEquals("tsc --noEmit", scripts.path("typecheck").asString());
        assertFalse(scaffold.render(contract, "Tasks", path -> path.equals("package.json") ? "existing custom manifest" : null)
                .containsKey("package.json"));
    }

    @Test
    void staticCompilerCannotReplaceAnExistingUnreviewedManifest() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        assertThrows(IllegalArgumentException.class, () -> scaffold.render(contract, "Bakery",
                path -> path.equals("package.json") ? "{\"scripts\":{\"build\":\"custom\"}}" : null));
    }

    @Test
    void v1UserCssNameRemainsReadableWhileV2ReservesItsCompiledArtifact() {
        var original = ContractFixtures.staticSite();
        var page = new BuildContract.Unit("ui", "Page and user styles", List.of("index.html", "rendra.css"),
                List.of(), List.of(), original.units().getFirst().acceptanceIds());
        var legacy = new BuildContract(1, original.id(), original.stack(), original.storage(), original.auth(),
                original.limits(), original.theme(), original.routes(), original.types(), original.models(), original.api(),
                original.assets(), original.envNames(), List.of(page), original.acceptance());
        assertEquals(legacy, mapper.readValue(mapper.writeValueAsString(legacy), BuildContract.class));
        assertThrows(IllegalArgumentException.class, () -> ContractPlans.bind(legacy, SpecFixtures.approved(),
                List.of(new ContractBatch("app", List.of("ui"), List.of(), page.ownedPaths(), List.of(),
                        page.acceptanceIds(), 32_000, 6_000, 4_000))));
    }
}
