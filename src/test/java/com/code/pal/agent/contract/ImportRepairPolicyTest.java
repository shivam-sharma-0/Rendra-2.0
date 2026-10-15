package com.code.pal.agent.contract;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.StructuredRepair;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImportRepairPolicyTest {
    private final ContractCoordinatorTest fixture = new ContractCoordinatorTest();
    private final BuildContract contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final StructuredRepair repairs = new StructuredRepair(fixture.project.files, fixture.changes, fixture.store, jdbc,
            fixture.mapper, fixture.props, leases);

    ImportRepairPolicyTest() {
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).guarded(any(), any(Runnable.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any())).thenReturn(1);
    }

    private ValidationReport report(AgentContext ctx, List<String> paths) {
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "tsc", "fixture",
                paths.stream().map(path -> new ProjectDiagnostic(path, 1, 1, DiagnosticSeverity.ERROR,
                        "Cannot find module react-router-dom", "compiler", "TS2307")).toList(),
                List.of(), 0, 2, fixture.changes.view(ctx).revision(), "Failed", List.of());
    }

    @Test
    void missingRouterPackageRepairGetsExactPolicyAndReplacesUsedSymbolsWithAnchors() {
        String before = "import { Link } from 'react-router-dom'; export default () => <Link to='/'>Home</Link>";
        String after = "export default () => <a href='/'>Home</a>";
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build tasks"), "Fixture");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/App.tsx", before);
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST"));
        repairs.repair(ctx, run, contract, contract.units(), report(ctx, List.of("src/App.tsx")), request -> {
            assertTrue(request.messages().getFirst().content().contains("For TS2307 on a disallowed package, remove the import"));
            assertTrue(request.messages().getFirst().content().contains("<a href"));
            var input = fixture.mapper.readTree(request.messages().get(1).content());
            var patch = new RepairPatch(1, input.path("batchId").asString(), input.path("diagnosticsRevision").asString(),
                    List.of(new RepairPatch.FilePatch("src/App.tsx", input.at("/files/src~1App.tsx/version").asString(),
                            List.of(new RepairPatch.Edit(before, after)))));
            return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
        }, () -> {});
        assertEquals(after, fixture.project.files.readText(ctx.projectId(), ctx.root(), "src/App.tsx"));
    }

    @Test
    void repairCannotIntroduceADisallowedImportAndNoEarlierPatchIsApplied() {
        String app = "export default () => <h1>Before</h1>";
        String state = "export const tasks = []";
        var run = fixture.run(ProjectType.BUILD);
        run.setRequestFingerprint("accepted");
        var ctx = fixture.context(run);
        ctx.plan().revise(List.of("Build tasks"), "Fixture");
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/App.tsx", app);
        fixture.project.files.writeText(ctx.projectId(), ctx.root(), "src/local-state.ts", state);
        when(fixture.store.forRun(ctx.projectId(), run.getId())).thenReturn(
                new ContractStore.Stored(run.getId(), ctx.projectId(), contract, "hash", fixture.binding, "APPROVED", "BUILD_REQUEST"));
        assertThrows(IllegalArgumentException.class, () -> repairs.repair(ctx, run, contract, contract.units(),
                report(ctx, List.of("src/App.tsx", "src/local-state.ts")), request -> {
                    var input = fixture.mapper.readTree(request.messages().get(1).content());
                    var patch = new RepairPatch(1, input.path("batchId").asString(), input.path("diagnosticsRevision").asString(),
                            List.of(new RepairPatch.FilePatch("src/App.tsx", input.at("/files/src~1App.tsx/version").asString(),
                                            List.of(new RepairPatch.Edit("Before", "After"))),
                                    new RepairPatch.FilePatch("src/local-state.ts", input.at("/files/src~1local-state.ts/version").asString(),
                                            List.of(new RepairPatch.Edit(state, "import router from 'react-router-dom'; " + state)))));
                    return new LlmClient.LlmResult(fixture.mapper.writeValueAsString(patch), null, List.of(), null, "stop");
                }, () -> {}));
        assertEquals(app, fixture.project.files.readText(ctx.projectId(), ctx.root(), "src/App.tsx"));
        assertEquals(state, fixture.project.files.readText(ctx.projectId(), ctx.root(), "src/local-state.ts"));
    }
}
