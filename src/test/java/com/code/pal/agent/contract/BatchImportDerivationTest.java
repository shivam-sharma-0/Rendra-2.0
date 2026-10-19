package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BatchImportDerivationTest {
    BuildContract contract(boolean sameBatch) {
        var base = ContractFixtures.vite();
        var parts = new BuildContract.Unit("parts", "Shared card", List.of("src/Card.tsx"), List.of(), List.of(), List.of());
        var app = new BuildContract.Unit("app", "App", List.of("src/App.tsx"), List.of(), List.of(), List.of("ui"));
        base = new BuildContract(1, base.id(), base.stack(), base.storage(), base.auth(), base.limits(), base.theme(),
                List.of(new BuildContract.Route("/", "src/App.tsx", "app")), base.types(), base.models(), base.api(),
                base.assets(), base.envNames(), List.of(parts, app), base.acceptance());
        var batches = sameBatch ? List.of(new ContractBatch("all", List.of("parts", "app"), List.of(),
                List.of("src/Card.tsx", "src/App.tsx"), List.of(), List.of("ui"), 32_000, 6_000, 1))
                : List.of(new ContractBatch("first", List.of("parts"), List.of(), List.of("src/Card.tsx"), List.of(), List.of(), 32_000, 6_000, 1),
                        new ContractBatch("second", List.of("app"), List.of(), List.of("src/App.tsx"), List.of(), List.of("ui"), 32_000, 6_000, 1));
        return ContractPlans.bind(base, SpecFixtures.browser(), batches);
    }

    @Test
    void anUndeclaredReadOfAnEarlierOwnedComponentIsDerivedWithoutChangingTheReviewedPlan() {
        var contract = contract(false);
        var app = contract.units().getLast();
        assertTrue(app.imports().isEmpty());
        assertEquals(Set.of("src/Card.tsx"), ContractImports.validate(contract, app, "src/App.tsx", "import Card from './Card'; export default Card"));
        assertTrue(app.imports().isEmpty());
        assertTrue(app.dependsOn().isEmpty());
    }

    @Test
    void sameBatchSiblingReadsAreAllowedButFutureUnownedAndOutsideImportsFail() {
        var together = contract(true);
        assertDoesNotThrow(() -> ContractImports.validate(together, together.units().getLast(), "src/App.tsx", "import Card from './Card'"));
        var separated = contract(false);
        assertThrows(IllegalArgumentException.class, () -> ContractImports.validate(separated, separated.units().getFirst(), "src/Card.tsx", "import App from './App'"));
        for (String module : Set.of("./Missing", "../../outside", "lucide-react")) {
            assertThrows(IllegalArgumentException.class, () -> ContractImports.validate(separated, separated.units().getLast(), "src/App.tsx", "import X from '" + module + "'"));
        }
    }

    @Test
    void inferredDependencyVersionsBindReceiptsWithoutSendingUnrelatedBodies() {
        var contract = contract(false);
        var batch = contract.batches().getLast();
        var app = contract.units().getLast();
        var first = new UnitGenerator.Input(contract, app, "Build", "Approved", Map.of(
                "src/App.tsx", new UnitGenerator.FileView("absent", null),
                "src/Card.tsx", new UnitGenerator.FileView("sha256:first", "UNRELATED_BODY")), "");
        var frozen = BatchUnitGenerator.plannedInputs(contract, batch, List.of(first));
        assertTrue(frozen.getFirst().snapshot().containsKey("src/Card.tsx"));
        assertNull(frozen.getFirst().snapshot().get("src/Card.tsx").content());
        var changed = new UnitGenerator.Input(contract, app, "Build", "Approved", Map.of(
                "src/App.tsx", new UnitGenerator.FileView("absent", null),
                "src/Card.tsx", new UnitGenerator.FileView("sha256:second", "OTHER_BODY")), "");
        var run = AgentRun.builder().requestFingerprint("synthetic").build();
        assertNotEquals(BatchReceipts.context(run, List.of(first), new AgentProperties(), new ObjectMapper()),
                BatchReceipts.context(run, List.of(changed), new AgentProperties(), new ObjectMapper()));
    }

    @Test
    void wholeBatchReceiptsKeepAcceptingDerivedImportsOnRecovery() {
        var contract = contract(false);
        var receipt = new BatchUnitGenerator.Output(List.of(new BatchUnitGenerator.UnitOutput("app",
                List.of(new UnitGenerator.Delta("src/App.tsx", "absent", "import Card from './Card'; export default Card")))));
        var recovered = BatchReceipts.validate(contract, contract.batches().getLast(), receipt);
        assertEquals(receipt.units().getFirst().files(), recovered.getFirst().files());
        assertEquals(Set.of("src/Card.tsx"), ContractImports.repairDependencies(contract, contract.units().getLast(), "src/App.tsx",
                "import MissingPackage from 'lucide-react'; import Card from './Card'"));
    }
}
