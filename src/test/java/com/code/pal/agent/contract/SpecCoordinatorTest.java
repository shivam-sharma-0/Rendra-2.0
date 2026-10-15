package com.code.pal.agent.contract;

import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SpecCoordinatorTest {
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final ContractStore.Binding binding = new ContractStore.Binding(1, fixture.binding.requirementsProjection(),
            fixture.binding.requirementsRevision(), "source", SpecFixtures.HASH.substring(7), Set.of());
    private final BatchBudget.Limits budget = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);

    SpecCoordinatorTest() {
        when(fixture.store.current(fixture.project.projectId)).thenReturn(binding);
    }

    @Test
    void smallApprovedSpecPlansWithoutAnyModelOrMutation() {
        var run = fixture.run(ProjectType.PLAN);
        var ctx = fixture.context(run);
        var spec = boundSpec();
        when(fixture.store.create(eq(run), any(), eq(binding), any())).thenAnswer(call ->
                new ContractStore.Stored(run.getId(), run.getProjectId(), call.getArgument(1), "hash", binding, "DRAFT", "EXPLICIT_PLAN"));
        var result = fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.S, List.of(), fixture.props),
                "Create a bakery page", "map", request -> { fail("No planner for the deterministic spec"); return null; },
                () -> fail("PLAN must not mutate"), activity -> {}, new ContractCoordinator.SpecPlanning(spec, budget));
        assertEquals(2, result.contract().schemaVersion());
        assertEquals(spec.specId(), result.contract().specId());
        assertEquals(1, result.contract().batches().size());
        verifyNoInteractions(fixture.validation, fixture.criteria, fixture.checkpoints);
    }

    @Test
    void unknownFeatureUsesOnePlannerInsteadOfDroppingTheFeature() {
        var original = boundSpec();
        var old = original.spec();
        var feature = new ApprovedSpec.Feature("content", "CUSTOM_WIDGET", "Bakery introduction", List.of("home"), List.of());
        var body = new ApprovedSpec.Spec(old.title(), old.language(), old.goal(), old.pages(), old.entities(), List.of(feature),
                old.theme(), old.acceptance(), old.assets(), old.constraints());
        var spec = rebody(original, body);
        var run = fixture.run(ProjectType.PLAN);
        var ctx = fixture.context(run);
        var count = new AtomicInteger();
        when(fixture.store.create(eq(run), any(), eq(binding), any())).thenAnswer(call ->
                new ContractStore.Stored(run.getId(), run.getProjectId(), call.getArgument(1), "hash", binding, "DRAFT", "EXPLICIT_PLAN"));
        fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.M, List.of(), fixture.props), "Create the bakery", "map", request -> {
            count.incrementAndGet();
            UUID id = UUID.fromString(fixture.mapper.readTree(request.messages().get(1).content()).path("id").asString());
            var layout = new DeterministicPlan(fixture.mapper).plan(original, budget).orElseThrow();
            var contract = ContractPlans.fromLayout(id, spec, layout, ContractFixtures.limits());
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(contract), null, List.of(), null, "stop");
        }, () -> fail("PLAN must not mutate"), activity -> {}, new ContractCoordinator.SpecPlanning(spec, budget));
        assertEquals(1, count.get());
    }

    @Test
    void mismatchedSpecAuthorityFailsBeforePlanning() {
        var run = fixture.run(ProjectType.PLAN);
        assertThrows(WorkflowStageException.class, () -> fixture.coordinator.execute(fixture.context(run), run,
                RunSize.of(SizeTier.S, List.of(), fixture.props), "Create bakery", "map",
                request -> { fail("No provider call for a foreign spec"); return null; }, () -> {}, activity -> {},
                new ContractCoordinator.SpecPlanning(SpecFixtures.approved(), budget)));
        verify(fixture.store, never()).create(any(), any(), any(), any());
    }

    @Test
    void smallNewAppIsEligibleWithASpecButQuestionsStillCannotBuild() {
        fixture.props.setStructuredContracts(true);
        var run = fixture.run(ProjectType.BUILD);
        var planning = new ContractCoordinator.SpecPlanning(boundSpec(), budget);
        var size = RunSize.of(SizeTier.S, List.of(), fixture.props);
        assertTrue(fixture.coordinator.eligible(run, size, "Build a bakery website", planning));
        assertFalse(fixture.coordinator.eligible(run, size, "How does this work?", planning));
    }

    @Test
    void approvedV2PlanBuildReusesItsExactContractWithOneSlicedBatchCall() {
        var spec = boundSpec();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var run = fixture.run(ProjectType.BUILD);
        var ctx = fixture.context(run);
        var stored = new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "EXPLICIT_PLAN");
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(stored);
        var calls = new AtomicInteger();
        var result = fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.M, List.of(), fixture.props),
                "build the approved plan", "map", request -> {
                    calls.incrementAndGet();
                    var data = fixture.mapper.readTree(request.messages().get(1).content());
                    assertFalse(data.has("contract"));
                    assertEquals(contract.batches().getFirst().id(), data.path("batch").path("id").asString());
                    var units = contract.units().stream().map(unit -> new BatchUnitGenerator.UnitOutput(unit.id(),
                            unit.ownedPaths().stream().map(path -> new UnitGenerator.Delta(path,
                                    data.path("snapshot").path(path).path("version").asString(),
                                    path.endsWith("html") ? "<h1>Solar Bakery</h1>" : "body { color: black }" )).toList())).toList();
                    return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(new BatchUnitGenerator.Output(units)),
                            null, List.of(), null, "stop");
                }, () -> {}, activity -> {}, new ContractCoordinator.SpecPlanning(spec, budget));
        assertEquals(contract, result.contract());
        assertEquals(1, calls.get());
        verify(fixture.store, never()).create(any(), any(), any(), any());
        assertFalse(fixture.project.exists("index.html"));
        verify(fixture.validation).validateAll(ctx);
    }

    @Test
    void legacyReviewedPlanCannotSilentlyStandInForCurrentSpec() {
        var run = fixture.run(ProjectType.BUILD);
        var stored = new ContractStore.Stored(run.getId(), run.getProjectId(), fixture.c, "hash", binding, "APPROVED", "EXPLICIT_PLAN");
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(stored);
        assertThrows(WorkflowStageException.class, () -> fixture.coordinator.execute(fixture.context(run), run,
                RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map",
                request -> { fail("Do not generate from an unrelated legacy plan"); return null; },
                () -> fail("No mutation before exact spec binding"), activity -> {}, new ContractCoordinator.SpecPlanning(boundSpec(), budget)));
    }

    @Test
    void newerSpecRevisionCannotReuseThePreviouslyReviewedContract() {
        var spec = boundSpec();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var next = new ApprovedSpec(spec.schemaVersion(), spec.specId(), spec.specRevision() + 1, spec.specHash(), spec.projectId(),
                spec.requirementsProjection(), spec.requirementsRevision(), spec.status(), spec.capabilityRevision(), spec.desired(), spec.readiness(), spec.spec());
        var run = fixture.run(ProjectType.BUILD);
        var stored = new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "EXPLICIT_PLAN");
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(stored);
        assertThrows(WorkflowStageException.class, () -> fixture.coordinator.execute(fixture.context(run), run,
                RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map",
                request -> { fail("No planner/generation for a stale reviewed spec"); return null; },
                () -> fail("No mutation for a stale reviewed spec"), activity -> {}, new ContractCoordinator.SpecPlanning(next, budget)));
    }

    private ApprovedSpec boundSpec() {
        var s = SpecFixtures.approved();
        return new ApprovedSpec(s.schemaVersion(), s.specId(), s.specRevision(), s.specHash(), fixture.project.projectId,
                binding.requirementsProjection(), binding.requirementsRevision(), s.status(),
                "sha256:" + binding.capabilityRevision(), s.desired(), s.readiness(), s.spec());
    }

    private ApprovedSpec rebody(ApprovedSpec s, ApprovedSpec.Spec body) {
        return new ApprovedSpec(s.schemaVersion(), s.specId(), s.specRevision(), s.specHash(), s.projectId(),
                s.requirementsProjection(), s.requirementsRevision(), s.status(), s.capabilityRevision(), s.desired(), s.readiness(), body);
    }
}
