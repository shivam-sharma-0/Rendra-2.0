package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.StructuredRepair;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowserRepairAttributionTest {
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final BuildContract contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final StructuredRepair repairs = new StructuredRepair(fixture.project.files, fixture.changes, fixture.store, jdbc,
            fixture.mapper, fixture.props, leases);

    BrowserRepairAttributionTest() {
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).guarded(any(), any(Runnable.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any())).thenReturn(1);
    }

    private ValidationReport failure(int revision, String message) {
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "browser", "fixture",
                List.of(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR, message, "runtime", "BROWSER_ACCEPTANCE_FAILED")),
                List.of(), 0, 2, revision, "Failed", List.of(new ValidationCheck("RUNTIME", ValidationCheck.Outcome.FAIL, true, "Interaction failed")));
    }

    @Test
    void filelessAcceptanceDiagnosticSelectsOnlyItsDeclaredOwnersAndSuppliesOrderedSteps() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build bakery"), "Fixture");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "index.html", "<h1>Before</h1>");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "styles.css", "body {}");
        var stored = new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST");
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(stored);
        var report = failure(fixture.changes.view(ctx).revision(), "Required interaction heading_0 did not pass");
        var owners = ContractCoordinator.affected(contract, report);
        assertEquals(List.of("app"), owners.stream().map(BuildContract.Unit::id).toList());
        var calls = new AtomicInteger();
        repairs.repair(ctx, run, contract, owners, report, request -> {
            calls.incrementAndGet();
            var data = fixture.mapper.readTree(request.messages().get(1).content());
            assertEquals(2, data.path("files").size());
            assertEquals("heading_0", data.at("/acceptance/failedIds/0").asString());
            assertEquals("ASSERT_TEXT", data.at("/acceptance/steps/0/kind").asString());
            assertEquals("Solar Bakery", data.at("/acceptance/steps/0/value").asString());
            assertEquals("Solar Bakery heading is visible", data.at("/acceptance/scenarios/0/expected").asString());
            var version = data.path("files").path("index.html").path("version").asString();
            var patch = new RepairPatch(1, data.path("batchId").asString(), data.path("diagnosticsRevision").asString(),
                    List.of(new RepairPatch.FilePatch("index.html", version, List.of(new RepairPatch.Edit("Before", "Solar Bakery")))));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
        }, () -> {}, specFor(ctx.projectId()));
        assertEquals(1, calls.get());
        assertEquals("<h1>Solar Bakery</h1>", fixture.project.files.readText(ctx.projectId(), ctx.root(), "index.html"));
    }

    private ApprovedSpec specFor(UUID projectId) {
        var spec = SpecFixtures.approved();
        return new ApprovedSpec(spec.schemaVersion(), spec.specId(), spec.specRevision(), spec.specHash(), projectId,
                spec.requirementsProjection(), spec.requirementsRevision(), spec.status(), spec.capabilityRevision(),
                spec.desired(), spec.readiness(), spec.spec());
    }

    @Test
    void unknownOrLookalikeAcceptanceIdsNeverBroadenThePatchScope() {
        for (String message : List.of("Required interaction heading_00 did not pass", "Required interaction foreign did not pass",
                "Some text Required interaction heading_0 did not pass")) {
            assertTrue(ContractCoordinator.affected(contract, failure(0, message)).isEmpty());
        }
    }

    @Test
    void failedCompletionKeepsEarlierCreateStepsInTheRepairContext() {
        var original = SpecFixtures.browser();
        var scenario = new ApprovedSpec.Acceptance("complete_task_scenario", "tasks", "BROWSER",
                List.of(new ApprovedSpec.Step("FILL", "input", "Buy milk"), new ApprovedSpec.Step("CLICK", "button", null),
                        new ApprovedSpec.Step("CLICK", "[data-task] input", null),
                        new ApprovedSpec.Step("ASSERT_COUNT", "[data-completed]", "1")), "Created task stays completed");
        var spec = SpecFixtures.with(original, original.spec().pages(), original.spec().entities(), original.spec().features(), List.of(scenario));
        var todo = new SpecPlanningTest().deterministic(spec);
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build tasks"), "Fixture");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/App.tsx", "export default () => <h1>Before</h1>");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/index.css", "body {}");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/local-state.ts", "export const tasks = []");
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), ctx.projectId(), todo, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST"));
        var report = failure(fixture.changes.view(ctx).revision(), "Required interaction complete_task_scenario_3 did not pass");
        repairs.repair(ctx, run, todo, ContractCoordinator.affected(todo, report), report, request -> {
            var input = fixture.mapper.readTree(request.messages().get(1).content());
            assertEquals(4, input.at("/acceptance/steps").size());
            assertEquals("FILL", input.at("/acceptance/steps/0/kind").asString());
            assertEquals("Buy milk", input.at("/acceptance/steps/0/value").asString());
            assertEquals("ASSERT_COUNT", input.at("/acceptance/steps/3/kind").asString());
            assertEquals("1", input.at("/acceptance/steps/3/value").asString());
            var patch = new RepairPatch(1, input.path("batchId").asString(), input.path("diagnosticsRevision").asString(),
                    List.of(new RepairPatch.FilePatch("src/App.tsx", input.at("/files/src~1App.tsx/version").asString(),
                            List.of(new RepairPatch.Edit("Before", "After")))));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
        }, () -> {});
        assertTrue(fixture.project.files.readText(ctx.projectId(), ctx.root(), "src/App.tsx").contains("After"));
    }

    @Test
    void filelessFailureNeverAuthorizesAnUnrelatedUnit() {
        var app = new BuildContract.Unit("app", "Bakery content", List.of("index.html"), List.of(), List.of(), List.of("heading_0"));
        var style = new BuildContract.Unit("style", "Presentation", List.of("styles.css"), List.of(), List.of(), List.of());
        var base = new BuildContract(1, contract.id(), contract.stack(), contract.storage(), contract.auth(), contract.limits(), contract.theme(),
                contract.routes(), contract.types(), contract.models(), contract.api(), contract.assets(), Set.of(), List.of(app, style), contract.acceptance());
        var scoped = ContractPlans.bind(base, SpecFixtures.approved(), List.of(new ContractBatch("app", List.of("app", "style"), List.of(),
                List.of("index.html", "styles.css"), List.of(), List.of("heading_0"), 32_000, 6_000, 1)));
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "index.html", "<h1>Before</h1>");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "styles.css", "body {}");
        var report = failure(fixture.changes.view(ctx).revision(), "Required interaction heading_0 did not pass");
        assertEquals(List.of(app), ContractCoordinator.affected(scoped, report));
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), ctx.projectId(), scoped, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST"));
        var marker = new IllegalStateException("Stop after inspecting scope");
        assertSame(marker, assertThrows(IllegalStateException.class, () -> repairs.repair(ctx, run, scoped, scoped.units(), report, request -> {
            var data = fixture.mapper.readTree(request.messages().get(1).content());
            assertEquals(1, data.path("files").size());
            assertTrue(data.path("files").has("index.html"));
            assertFalse(data.path("files").has("styles.css"));
            throw marker;
        }, () -> {})));
    }
}
