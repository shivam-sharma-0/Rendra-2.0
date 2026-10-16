package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.repair.RepairJournal;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepairJournalTest {
    private final LocalProject project = LocalProject.create();
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String revision = "sha256:" + "d".repeat(64);

    RepairJournalTest() {
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(any(), any(Runnable.class));
    }

    @Test
    void reservationSurvivesRestartAndNeverAllowsAThirdRound() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        journal.reserve(run, fixture.c, revision, () -> {});
        var restarted = new RepairJournal(project.files, leases, mapper);
        restarted.reserve(run, fixture.c, revision, () -> {});
        assertThrows(IllegalStateException.class, () -> restarted.reserve(run, fixture.c, revision, () -> {}));
    }

    @Test
    void preparedPartialMergeRecoversOnceWithoutAnotherModelRound() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        project.files.writeText(run.getProjectId(), root, "a.ts", "before a");
        project.files.writeText(run.getProjectId(), root, "b.ts", "before b");
        int round = journal.reserve(run, fixture.c, revision, () -> {});
        var deltas = List.of(new UnitGenerator.Delta("a.ts", "sha256:" + ContractStore.hash("before a"), "after a"),
                new UnitGenerator.Delta("b.ts", "sha256:" + ContractStore.hash("before b"), "after b"));
        journal.prepare(run, fixture.c, root, round, deltas, () -> {});
        project.files.writeText(run.getProjectId(), root, "a.ts", "after a"); // Simulated process loss, not an actual kill.
        var restarted = new RepairJournal(project.files, leases, mapper);
        var applied = new AtomicBoolean();
        restarted.recover(run, fixture.c, root, () -> {}, changes -> {
            applied.set(true);
            changes.forEach(delta -> project.files.writeText(run.getProjectId(), root, delta.path(), delta.content()));
        });
        assertTrue(applied.get());
        assertEquals("after b", project.files.readText(run.getProjectId(), root, "b.ts"));
        assertEquals("after a", restarted.overlay(run, fixture.c, List.of(new UnitGenerator.Output(List.of(deltas.getFirst()))))
                .getFirst().files().getFirst().content());
        applied.set(false);
        restarted.recover(run, fixture.c, root, () -> {}, changes -> applied.set(true));
        assertFalse(applied.get(), "An already recorded apply must not be replayed again");
    }

    @Test
    void preparedOverlayBindsGeneratedIntermediateAndRestoredBaselineVersions() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        String generatedVersion = "sha256:" + ContractStore.hash("generated");
        String baselineVersion = "sha256:" + ContractStore.hash("live baseline");
        project.files.writeText(run.getProjectId(), root, "index.html", "generated");
        int round = journal.reserve(run, fixture.c, revision, () -> {});
        journal.prepare(run, fixture.c, root, round, List.of(new UnitGenerator.Delta("index.html", generatedVersion, "repaired")), () -> {});
        var generation = List.of(new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html", baselineVersion, "generated"))));
        var intermediate = journal.overlay(run, fixture.c, generation, Map.of("index.html", generatedVersion));
        assertEquals(generatedVersion, intermediate.getFirst().files().getFirst().expectedVersion());
        assertEquals("repaired", intermediate.getFirst().files().getFirst().content());
        var restored = journal.overlay(run, fixture.c, generation, Map.of("index.html", baselineVersion));
        assertEquals(baselineVersion, restored.getFirst().files().getFirst().expectedVersion());
        var foreign = journal.overlay(run, fixture.c, generation, Map.of("index.html", "sha256:" + ContractStore.hash("foreign")));
        assertEquals(baselineVersion, foreign.getFirst().files().getFirst().expectedVersion(), "Unproved stage versions are never blessed");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void regeneratedBodiesCannotInheritARepairForDifferentGeneratedSource(boolean applied) {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        project.files.writeText(run.getProjectId(), root, "index.html", "old generated body");
        int round = journal.reserve(run, fixture.c, revision, () -> {});
        var delta = new UnitGenerator.Delta("index.html", "sha256:" + ContractStore.hash("old generated body"), "old repaired body");
        journal.prepare(run, fixture.c, root, round, List.of(delta), () -> {});
        if (applied) {
            journal.recover(run, fixture.c, root, () -> {}, changes -> changes.forEach(file ->
                    project.files.writeText(run.getProjectId(), root, file.path(), file.content())));
        }
        var fresh = List.of(new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html", "absent", "new generated body"))));
        assertEquals("new generated body", journal.overlay(run, fixture.c, fresh).getFirst().files().getFirst().content());
        journal.recover(run, fixture.c, root, () -> {}, changes -> fail("An invalidated receipt must never replay"));
        var state = mapper.readValue(project.files.readText(run.getProjectId(), ".codepal/repairs/" + run.getId(), "journal.json"), RepairJournal.Envelope.class);
        assertEquals("INVALIDATED", state.state().rounds().getFirst().state());
        assertEquals(2, journal.reserve(run, fixture.c, revision, () -> {}), "Invalidation must keep the first round spent");
    }

    @Test
    void invalidationDropsOnlyTheUnrelatedPathFromAPreparedRound() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        project.files.writeText(run.getProjectId(), root, "a.ts", "before a");
        project.files.writeText(run.getProjectId(), root, "b.ts", "before b");
        int round = journal.reserve(run, fixture.c, revision, () -> {});
        journal.prepare(run, fixture.c, root, round, List.of(
                new UnitGenerator.Delta("a.ts", "sha256:" + ContractStore.hash("before a"), "after a"),
                new UnitGenerator.Delta("b.ts", "sha256:" + ContractStore.hash("before b"), "after b")), () -> {});
        var output = new UnitGenerator.Output(List.of(new UnitGenerator.Delta("a.ts", "absent", "fresh a"),
                new UnitGenerator.Delta("b.ts", "absent", "before b")));
        var overlaid = journal.overlay(run, fixture.c, List.of(output)).getFirst().files();
        assertEquals("fresh a", overlaid.getFirst().content());
        assertEquals("after b", overlaid.getLast().content());
        journal.recover(run, fixture.c, root, () -> {}, changes -> {
            assertEquals(List.of("b.ts"), changes.stream().map(UnitGenerator.Delta::path).toList());
            changes.forEach(file -> project.files.writeText(run.getProjectId(), root, file.path(), file.content()));
        });
        assertEquals("before a", project.files.readText(run.getProjectId(), root, "a.ts"));
    }

    @Test
    void twoRepairRoundsReplayOnlyFromAKnownGenerationOrRepairBody() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        project.files.writeText(run.getProjectId(), root, "index.html", "generated");
        for (String after : List.of("first repair", "second repair")) {
            int round = journal.reserve(run, fixture.c, revision, () -> {});
            String before = project.files.version(run.getProjectId(), root, "index.html");
            journal.prepare(run, fixture.c, root, round, List.of(new UnitGenerator.Delta("index.html", before, after)), () -> {});
            journal.recover(run, fixture.c, root, () -> {}, changes -> changes.forEach(file ->
                    project.files.writeText(run.getProjectId(), root, file.path(), file.content())));
        }
        for (String body : List.of("generated", "first repair", "second repair")) {
            var generation = List.of(new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html", "absent", body))));
            assertEquals("second repair", journal.overlay(run, fixture.c, generation).getFirst().files().getFirst().content());
        }
    }

    @Test
    void unknownMixedTargetIsQuarantinedAndStopCannotReserve() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("request");
        var journal = new RepairJournal(project.files, leases, mapper);
        String root = ".codepal/repair-fixture";
        project.files.writeText(run.getProjectId(), root, "index.html", "before");
        int round = journal.reserve(run, fixture.c, revision, () -> {});
        journal.prepare(run, fixture.c, root, round, List.of(new UnitGenerator.Delta("index.html", "sha256:" + ContractStore.hash("before"), "after")), () -> {});
        project.files.writeText(run.getProjectId(), root, "index.html", "foreign");
        assertThrows(IllegalStateException.class, () -> journal.recover(run, fixture.c, root, () -> {}, changes -> fail("No unproved writes")));
        project.files.writeText(run.getProjectId(), root, "index.html", "before");
        assertThrows(IllegalStateException.class, () -> journal.recover(run, fixture.c, root, () -> {}, changes -> fail("Quarantine is durable")));
        var other = fixture.run(ProjectType.BUILD);
        other.setRequestFingerprint("other");
        assertThrows(IllegalStateException.class, () -> journal.reserve(other, fixture.c, revision, () -> { throw new IllegalStateException("Stop"); }));
    }
}
