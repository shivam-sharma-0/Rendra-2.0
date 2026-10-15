package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.StructuredRepair;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuredRepairTest {
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final BuildContract contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final StructuredRepair repairs = new StructuredRepair(fixture.project.files, fixture.changes, fixture.store, jdbc,
            fixture.mapper, fixture.props, leases);

    StructuredRepairTest() {
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(any(), any(Runnable.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any())).thenReturn(1);
    }

    private ValidationReport report(int revision, String path) {
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "npm", "fixture",
                List.of(new ProjectDiagnostic(path, null, null, DiagnosticSeverity.ERROR, "Fix the heading", "fixture", "WRONG_HEADING")),
                List.of(), 0, 2, revision, "Failure", List.of());
    }

    @Test
    void scopedPatchChangesOnlyTheDiagnosedStagedFileAndUsesOneCall() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build bakery"), "Fixture");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "index.html", "<h1>Before</h1>");
        var approved = new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST");
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(approved);
        var calls = new AtomicInteger();
        repairs.repair(ctx, run, contract, contract.units(), report(fixture.changes.view(ctx).revision(), "index.html"), request -> {
            calls.incrementAndGet();
            assertTrue(request.tools().isEmpty());
            var input = fixture.mapper.readTree(request.messages().get(1).content());
            assertEquals(1, input.path("files").size());
            var patch = new RepairPatch(1, input.path("batchId").asString(), input.path("diagnosticsRevision").asString(),
                    List.of(new RepairPatch.FilePatch("index.html", input.path("files").path("index.html").path("version").asString(),
                            List.of(new RepairPatch.Edit("Before", "After")))));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
        }, () -> {});
        assertEquals(1, calls.get());
        assertEquals("<h1>After</h1>", fixture.project.files.readText(ctx.projectId(), ctx.root(), "index.html"));
        assertFalse(fixture.project.exists("index.html"));
        assertTrue(fixture.changes.hasUnvalidatedChanges(ctx));
    }

    @Test
    void unattributedOrSharedInterfaceFailureMakesNoModelCall() {
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        assertThrows(IllegalStateException.class, () -> repairs.repair(ctx, run, contract, contract.units(),
                report(fixture.changes.view(ctx).revision(), contract.clientRoot() + "theme.css"), request -> {
                    fail("No regeneration or ABI guess for unowned diagnostics"); return null;
                }, () -> fail("No durable reservation before attribution")));
    }
}
