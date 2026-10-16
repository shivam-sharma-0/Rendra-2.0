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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissingOwnedRepairTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOwnedFileCanBeCreatedButAConcurrentCreationIsNeverOverwritten(boolean concurrentCreate) {
        var fixture = new ContractCoordinatorTest();
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build bakery"), "Fixture");
        var jdbc = mock(JdbcTemplate.class);
        var leases = mock(ProjectLeaseService.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).guarded(any(), any(Runnable.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any())).thenReturn(1);
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST"));
        var report = new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "build", "fixture",
                List.of(new ProjectDiagnostic("styles.css", null, null, DiagnosticSeverity.ERROR, "Missing stylesheet", "compiler", "MISSING_FILE")),
                List.of(), 0, 2, fixture.changes.view(ctx).revision(), "Failed", List.of());
        var repairs = new StructuredRepair(fixture.project.files, fixture.changes, fixture.store, jdbc, fixture.mapper, fixture.props, leases);
        var calls = new AtomicInteger();
        Runnable repair = () -> repairs.repair(ctx, run, contract, contract.units(), report, request -> {
            calls.incrementAndGet();
            var input = fixture.mapper.readTree(request.messages().get(1).content());
            assertEquals("absent", input.at("/files/styles.css/version").asString());
            assertTrue(input.at("/files/styles.css/content").isNull());
            if (concurrentCreate) {
                fixture.project.files.writeText(ctx.projectId(), ctx.root(), "styles.css", "/* concurrent owner */");
            }
            var patch = new RepairPatch(1, input.path("batchId").asString(), input.path("diagnosticsRevision").asString(),
                    List.of(new RepairPatch.FilePatch("styles.css", "absent", List.of(new RepairPatch.Edit("", "body { color: black }")))));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
        }, () -> {});
        if (concurrentCreate) {
            assertThrows(IllegalStateException.class, repair::run);
            assertEquals("/* concurrent owner */", fixture.project.files.readText(ctx.projectId(), ctx.root(), "styles.css"));
        } else {
            repair.run();
            repairs.recover(ctx, run, contract);
            repairs.recover(ctx, run, contract);
            assertEquals("body { color: black }", fixture.project.files.readText(ctx.projectId(), ctx.root(), "styles.css"));
            assertFalse(fixture.project.exists("styles.css"));
        }
        assertEquals(1, calls.get());
    }
}
