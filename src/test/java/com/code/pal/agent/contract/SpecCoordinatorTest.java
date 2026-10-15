package com.code.pal.agent.contract;

import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void attachedV2ContractRequiresItsSpecEvenAfterStageIsNoLongerEmpty() {
        var spec = boundSpec();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var run = fixture.run(ProjectType.BUILD);
        fixture.project.write("index.html", "Previously generated stage");
        var stored = new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "BUILD_REQUEST");
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(stored);
        assertTrue(fixture.coordinator.needsSpecPlanning(run));
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(null);
        assertFalse(fixture.coordinator.needsSpecPlanning(run), "Ordinary edits must keep the existing tool-loop path");
    }

    @Test
    void bundleAdapterReplacesLegacyCompilerAndFinalValidationCalls() {
        var spec = boundSpec();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var run = fixture.run(ProjectType.BUILD);
        var ctx = fixture.context(run);
        var approved = new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "EXPLICIT_PLAN");
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(approved);
        var phases = new ArrayList<ValidationRequest.Phase>();
        fixture.coordinator.useBundleValidation((context, owner, plan, batch, completed) -> {
            var phase = batch == null ? ValidationRequest.Phase.FINAL : ValidationRequest.Phase.BATCH;
            phases.add(phase);
            var identity = new ValidationRequest(run.getId(), ctx.projectId(), contract.id(), contract.planRevision(), batch,
                    fixture.changes.view(ctx).revision(), "source", "deps", "commands", "criteria", "caps", "validator", phase,
                    Set.of("BUILD", "RUNTIME", "BASELINE"), completed);
            var command = new ValidationBundle.CommandReceipt("BUILD", "identity", ValidationCheck.Outcome.PASS, "artifact");
            var checks = List.of(new ValidationCheck("BUILD", ValidationCheck.Outcome.PASS, true, ""),
                    new ValidationCheck("RUNTIME", ValidationCheck.Outcome.PASS, true, ""),
                    new ValidationCheck("BASELINE", ValidationCheck.Outcome.PASS, true, ""));
            return new ValidationBundle(identity, "sha256:" + "d".repeat(64), checks, List.of(), List.of(command), "artifact", true);
        });
        fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map", request -> {
            var data = fixture.mapper.readTree(request.messages().get(1).content());
            var units = contract.units().stream().map(unit -> new BatchUnitGenerator.UnitOutput(unit.id(), unit.ownedPaths().stream()
                    .map(path -> new UnitGenerator.Delta(path, data.path("snapshot").path(path).path("version").asString(),
                            path.endsWith("html") ? "<h1>Solar Bakery</h1>" : "body {}" )).toList())).toList();
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(new BatchUnitGenerator.Output(units)), null, List.of(), null, "stop");
        }, () -> {}, activity -> {}, new ContractCoordinator.SpecPlanning(spec, budget));
        assertEquals(List.of(ValidationRequest.Phase.BATCH, ValidationRequest.Phase.FINAL), phases);
        verifyNoInteractions(fixture.validation);
    }

    @Test
    void recoveredWholeBatchUsesNoModelButStillNeedsFreshFinalValidation() {
        var spec = boundSpec();
        var contract = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var run = fixture.run(ProjectType.BUILD);
        var ctx = fixture.context(run);
        var cache = mock(UnitOutputCheckpoints.class);
        fixture.coordinator.useUnitCheckpoints(cache);
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "EXPLICIT_PLAN"));
        when(cache.reuseBatch(eq(run), anyList(), eq(ctx))).thenAnswer(call -> {
            List<UnitGenerator.Input> inputs = call.getArgument(1);
            return inputs.stream().map(input -> new UnitGenerator.Output(input.unit().ownedPaths().stream()
                    .map(path -> new UnitGenerator.Delta(path, input.snapshot().get(path).version(),
                            path.endsWith("html") ? "<h1>Solar Bakery</h1>" : "body {}"))
                    .toList())).toList();
        });
        fixture.coordinator.execute(ctx, run, RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map",
                request -> { fail("An intact whole-batch receipt must not call a model"); return null; }, () -> {}, activity -> {},
                new ContractCoordinator.SpecPlanning(spec, budget));
        verify(cache, never()).saveBatch(any(), anyList(), anyList());
        verify(cache).verified(run);
        verify(fixture.validation).validateAll(ctx);
        assertFalse(fixture.project.exists("index.html"));
    }

    @Test
    void stopBetweenPlannedBatchesPreventsTheNextModelCallAndVerification() {
        var spec = boundSpec();
        var old = ContractPlans.fromLayout(UUID.randomUUID(), spec,
                new DeterministicPlan(fixture.mapper).plan(spec, budget).orElseThrow(), ContractFixtures.limits());
        var page = new BuildContract.Unit("page", "Bakery page", List.of("index.html"), List.of(), List.of(),
                old.units().getFirst().acceptanceIds());
        var style = new BuildContract.Unit("style", "Bakery style", List.of("styles.css"), List.of(), List.of("page"), List.of());
        var layout = new BuildContract(1, old.id(), old.stack(), old.storage(), old.auth(), old.limits(), old.theme(),
                List.of(new BuildContract.Route("/", "index.html", "page")), old.types(), old.models(), old.api(), old.assets(),
                old.envNames(), List.of(page, style), old.acceptance());
        var contract = ContractPlans.bind(layout, spec, List.of(
                new ContractBatch("pageBatch", List.of("page"), List.of(), page.ownedPaths(), List.of(), page.acceptanceIds(), 32_000, 6_000, 4_000),
                new ContractBatch("styleBatch", List.of("style"), List.of("pageBatch"), style.ownedPaths(), List.of(), List.of(), 32_000, 6_000, 4_000)));
        var run = fixture.run(ProjectType.BUILD);
        var ctx = fixture.context(run);
        var cache = mock(UnitOutputCheckpoints.class);
        fixture.coordinator.useUnitCheckpoints(cache);
        when(fixture.store.forRun(run.getProjectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), run.getProjectId(), contract, "hash", binding, "APPROVED", "EXPLICIT_PLAN"));
        var stopped = new AtomicBoolean();
        when(fixture.validation.run(eq(ctx), eq(ValidationOperation.BUILD), isNull())).thenAnswer(call -> {
            stopped.set(true);
            return fixture.report(true, List.of());
        });
        var calls = new AtomicInteger();
        assertThrows(LlmCancelledException.class, () -> fixture.coordinator.execute(ctx, run,
                RunSize.of(SizeTier.M, List.of(), fixture.props), "build the approved plan", "map", request -> {
                    calls.incrementAndGet();
                    var data = fixture.mapper.readTree(request.messages().get(1).content());
                    var delta = new UnitGenerator.Delta("index.html", data.path("snapshot").path("index.html").path("version").asString(),
                            "<h1>Solar Bakery</h1>");
                    return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(new BatchUnitGenerator.Output(
                            List.of(new BatchUnitGenerator.UnitOutput("page", List.of(delta))))), null, List.of(), null, "stop");
                }, () -> { if (stopped.get()) throw new LlmCancelledException(); }, activity -> {},
                new ContractCoordinator.SpecPlanning(spec, budget)));
        assertEquals(1, calls.get());
        verify(cache, times(1)).saveBatch(eq(run), anyList(), anyList());
        verify(cache, never()).verified(any());
        verify(fixture.validation, never()).validateAll(any());
        assertFalse(fixture.project.exists("index.html"));
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

    @Test
    void disconnectedSupabaseCannotGenerateOrFallBackToBrowserStorage() {
        var original = boundSpec();
        var spec = new ApprovedSpec(original.schemaVersion(), original.specId(), original.specRevision(), original.specHash(), original.projectId(),
                original.requirementsProjection(), original.requirementsRevision(), original.status(), original.capabilityRevision(),
                new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE, ApprovedSpec.Storage.SUPABASE, ApprovedSpec.Auth.NONE),
                ApprovedSpec.Readiness.CONNECTION_REQUIRED, original.spec());
        var run = fixture.run(ProjectType.BUILD);
        assertThrows(WorkflowStageException.class, () -> fixture.coordinator.execute(fixture.context(run), run,
                RunSize.of(SizeTier.M, List.of(), fixture.props), "Build the shared app", "map",
                request -> { fail("Disconnected Supabase must never generate a local fallback"); return null; },
                () -> fail("Disconnected Supabase must not stage changes"), activity -> {}, new ContractCoordinator.SpecPlanning(spec, budget)));
        verify(fixture.store, never()).create(any(), any(), any(), any());
    }

    private ApprovedSpec rebody(ApprovedSpec s, ApprovedSpec.Spec body) {
        return new ApprovedSpec(s.schemaVersion(), s.specId(), s.specRevision(), s.specHash(), s.projectId(),
                s.requirementsProjection(), s.requirementsRevision(), s.status(), s.capabilityRevision(), s.desired(), s.readiness(), body);
    }
}
