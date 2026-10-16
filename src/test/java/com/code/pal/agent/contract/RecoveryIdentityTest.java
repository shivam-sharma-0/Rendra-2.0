package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.repair.RepairJournal;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Seed persisted canonical proofs, independently of the production identity implementation. */
class RecoveryIdentityTest {
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final BuildContract contract = contractWithUnorderedCollections();
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);

    RecoveryIdentityTest() {
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).guarded(any(), any(Runnable.class));
    }

    private BuildContract contractWithUnorderedCollections() {
        var node = (ObjectNode) fixture.mapper.valueToTree(ContractFixtures.mern());
        node.set("envNames", fixture.mapper.valueToTree(List.of("PORT", "NODE_ENV", "MONGODB_URI")));
        return fixture.mapper.readValue(fixture.mapper.writeValueAsString(node), BuildContract.class);
    }

    private String canonicalContract() {
        return canonicalContract(contract);
    }

    private String canonicalContract(BuildContract contract) {
        var node = (ObjectNode) fixture.mapper.valueToTree(contract);
        node.set("envNames", fixture.mapper.valueToTree(new TreeSet<>(contract.envNames())));
        return ContractStore.hash(fixture.mapper.writeValueAsString(sorted(node)));
    }

    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            var result = new TreeMap<String, Object>();
            node.properties().forEach(entry -> result.put(entry.getKey(), sorted(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            return StreamSupport.stream(node.spliterator(), false).map(this::sorted).toList();
        }
        return node;
    }

    @Test
    void journalReopensAgainstCanonicalContractAfterWorkerRestart() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var state = new RepairJournal.State("accepted", canonicalContract(),
                List.of(new RepairJournal.Round(1, "sha256:" + "d".repeat(64), "RESERVED", List.of())));
        fixture.project.files.writeText(run.getProjectId(), ".codepal/repairs/" + run.getId(), "journal.json",
                fixture.mapper.writeValueAsString(new RepairJournal.Envelope(
                        ContractStore.hash(fixture.mapper.writeValueAsString(state)), state)));
        var reread = fixture.mapper.readValue(fixture.mapper.writeValueAsString(contract), BuildContract.class);
        assertEquals(2, new RepairJournal(fixture.project.files, leases, fixture.mapper)
                .reserve(run, reread, "sha256:" + "e".repeat(64), () -> {}));
    }

    @Test
    void fixerReopensCanonicalClaimAndDoesNotSpendItTwice() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var state = new FixAttempts.State("accepted", canonicalContract(), Set.of("spent"));
        fixture.project.files.writeText(run.getProjectId(), ".codepal/fix-attempts/" + run.getId(), "attempts.json",
                fixture.mapper.writeValueAsString(state));
        var attempts = new FixAttempts(fixture.project.files, leases, fixture.mapper);
        assertFalse(attempts.claim(run, contract, new DeterministicFixers.Fix(Map.of("index.html", "fixed"), false, "spent"), () -> {}));
        assertTrue(attempts.claim(run, contract, new DeterministicFixers.Fix(Map.of("index.html", "fixed"), false, "next"), () -> {}));
    }

    @Test
    void batchReceiptUsesTheSameCanonicalContractIdentity() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        var batch = contract.batches().getFirst();
        var snapshot = new TreeMap<String, UnitGenerator.FileView>();
        batch.ownedPaths().forEach(path -> snapshot.put(path, new UnitGenerator.FileView("absent", null)));
        batch.interfaces().forEach(shared -> snapshot.put(shared.path(), new UnitGenerator.FileView(shared.hash(), null)));
        contract.units().forEach(unit -> unit.imports().forEach(path -> snapshot.putIfAbsent(path, new UnitGenerator.FileView("absent", null))));
        var inputs = contract.units().stream().filter(unit -> batch.unitIds().contains(unit.id()))
                .map(unit -> new UnitGenerator.Input(contract, unit, "", "", snapshot, "")).toList();
        var versions = new TreeMap<String, String>();
        snapshot.forEach((path, file) -> {
            if (!batch.ownedPaths().contains(path)) {
                versions.put(path, file.version());
            }
        });
        String expected = ContractStore.hash("batch-receipt-v1/GENERATION/accepted/" + canonicalContract(contract) + "/" + batch.id() + "/"
                + ContractScaffold.revision() + "/" + fixture.props.getMaxRepairAttempts() + "/" + fixture.props.getSandbox().isRequired()
                + "/" + fixture.props.getSandbox().isRuntimeCheck() + "/" + versions);
        assertEquals(expected, BatchReceipts.context(run, inputs, fixture.props, fixture.mapper));
    }

    @Test
    void changedContractCannotReuseTheCanonicalRepairIdentity() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var journal = new RepairJournal(fixture.project.files, leases, fixture.mapper);
        journal.reserve(run, contract, "sha256:" + "d".repeat(64), () -> {});
        var node = (ObjectNode) fixture.mapper.valueToTree(contract);
        ((ObjectNode) node.path("theme")).put("accent", "#abcdef");
        var changed = fixture.mapper.readValue(fixture.mapper.writeValueAsString(node), BuildContract.class);
        assertThrows(IllegalStateException.class, () -> journal.reserve(run, changed, "sha256:" + "e".repeat(64), () -> {}));
    }
}
