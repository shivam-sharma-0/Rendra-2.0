package com.code.pal.agent.contract;

import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractScaffoldRouterTest {
    static BuildContract routed() {
        var original = ContractFixtures.vite();
        var owner = new BuildContract.Unit("ui", "Two pages", List.of("src/Home.tsx", "src/Settings.tsx"), List.of(), List.of(), List.of("ui"));
        var layout = new BuildContract(1, original.id(), original.stack(), original.storage(), original.auth(), original.limits(), original.theme(),
                List.of(new BuildContract.Route("/", "src/Home.tsx", "ui"), new BuildContract.Route("/settings", "src/Settings.tsx", "ui")),
                original.types(), original.models(), original.api(), original.assets(), original.envNames(), List.of(owner), original.acceptance());
        return ContractPlans.bind(layout, SpecFixtures.browser(), List.of(new ContractBatch("app", List.of("ui"), List.of(),
                owner.ownedPaths(), List.of(), owner.acceptanceIds(), 32_000, 6_000, 4_000)));
    }

    @Test
    void generatedBootstrapHasARealNotFoundViewWithoutReplacingAnExistingApp() {
        var contract = routed();
        var scaffold = new ContractScaffold(new ObjectMapper());
        var output = scaffold.render(contract, "Fixture", path -> null);
        assertTrue(output.get("src/App.tsx").contains("Page not found"));
        assertTrue(output.get(contract.clientRoot() + "routes.ts").contains("/settings"));
        assertFalse(scaffold.render(contract, "Fixture", path -> path.equals("src/App.tsx") ? "existing custom router" : null)
                .containsKey("src/App.tsx"));
    }
}
