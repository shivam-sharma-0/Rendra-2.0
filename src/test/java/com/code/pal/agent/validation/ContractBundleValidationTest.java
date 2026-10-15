package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractBundleValidationTest {
    @TempDir
    Path workspace;
    private final UUID project = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final ProjectValidationService validation = mock(ProjectValidationService.class);
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final ContractStore store = mock(ContractStore.class);
    private final RunBehaviorCriteria criteria = mock(RunBehaviorCriteria.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final AgentRun run = mock(AgentRun.class);
    private final BuildContract contract = mock(BuildContract.class);
    private final AgentContext ctx = mock(AgentContext.class);
    private HostProjectFiles files;
    private ContractBundleValidation adapter;
    private int revision = 3;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void open() {
        var props = new AgentProperties();
        props.setWorkspaceBasePath(workspace.toString());
        files = new HostProjectFiles(new WorkspaceStorage(props));
        files.writeText(project, "stage", "index.html", "<h1>Bakery</h1>");
        files.writeText(project, "stage", "package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        when(ctx.projectId()).thenReturn(project);
        when(run.getId()).thenReturn(runId);
        when(contract.id()).thenReturn(UUID.randomUUID());
        when(contract.planRevision()).thenReturn(1L);
        when(changes.view(ctx)).thenAnswer(call -> new ChangeSetService.WorkspaceView(".", "stage", "c", revision, List.of()));
        when(store.current(project)).thenReturn(new ContractStore.Binding(1, UUID.randomUUID(), 1, "code", "c".repeat(64), Set.of()));
        when(validation.plannedCommand(ctx, ValidationOperation.TYPE_CHECK)).thenReturn(List.of("npm", "run", "typecheck"));
        when(validation.plannedCommand(ctx, ValidationOperation.BUILD)).thenReturn(List.of("npm", "run", "build"));
        when(validation.run(eq(ctx), any(), isNull())).thenAnswer(call -> report(call.getArgument(1), true, List.of()));
        when(leases.guarded(eq(project), any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(eq(project), any(Runnable.class));
        adapter = new ContractBundleValidation(validation, changes, files, store, criteria, leases, new ObjectMapper());
    }

    @Test
    void aBatchCompilesOnceAndReusesItsBoundReceiptsForTheSameSources() {
        var first = adapter.validateBundle(ctx, run, contract, "pageBatch", Set.of());
        assertTrue(first.checks().stream().allMatch(check -> check.outcome() == ValidationCheck.Outcome.PASS), first.checks().toString());
        assertFalse(first.certifyEligible(), "a batch never certifies the final app");

        adapter.validateBundle(ctx, run, contract, "styleBatch", Set.of("pageBatch"));
        verify(validation, times(1)).run(ctx, ValidationOperation.BUILD, null);
        verify(validation, times(1)).run(ctx, ValidationOperation.TYPE_CHECK, null);

        files.writeText(project, "stage", "index.html", "<h1>Bakery and cafe</h1>");
        revision = 4;
        adapter.validateBundle(ctx, run, contract, "styleBatch", Set.of("pageBatch"));
        verify(validation, times(2)).run(ctx, ValidationOperation.BUILD, null);
    }

    @Test
    void theFinalGateRunsFullValidationOnTheSameRevisionAndCanCertify() {
        when(validation.validateAll(ctx)).thenReturn(report(ValidationOperation.BUILD, true, List.of(
                pass("STRUCTURE"), pass("TYPE_CHECK"), pass("BUILD"), pass("RUNTIME"), pass("BASELINE"))));

        var bundle = adapter.validateBundle(ctx, run, contract, null, Set.of("pageBatch"));

        assertTrue(bundle.certifyEligible(), bundle.checks().toString());
        assertEquals(Set.of("TYPE_CHECK", "BUILD", "STRUCTURE", "RUNTIME", "BASELINE"), bundle.requestIdentity().requiredChecks());
    }

    @Test
    void aFinalCompilerFailureIsRepairableAndSkipsTheBrowserAsAPrerequisite() {
        var error = new ProjectDiagnostic("src/App.tsx", 1, 1, DiagnosticSeverity.ERROR, "Type error", "typecheck", "TS2304");
        when(validation.run(eq(ctx), eq(ValidationOperation.TYPE_CHECK), isNull()))
                .thenReturn(report(ValidationOperation.TYPE_CHECK, false, List.of(), List.of(error)));

        var bundle = adapter.validateBundle(ctx, run, contract, null, Set.of());

        verify(validation, never()).validateAll(any());
        assertFalse(bundle.certifyEligible());
        assertEquals(List.of(error), bundle.diagnostics());
        assertTrue(bundle.checks().stream().noneMatch(ValidationCheck::unavailable), bundle.checks().toString());
    }

    @Test
    void aStaticSiteWithoutATypeCheckDoesNotRequireOne() {
        when(validation.plannedCommand(ctx, ValidationOperation.TYPE_CHECK)).thenReturn(null);
        var bundle = adapter.validateBundle(ctx, run, contract, "pageBatch", Set.of());
        assertEquals(Set.of("BUILD"), bundle.requestIdentity().requiredChecks());
        verify(validation, never()).run(ctx, ValidationOperation.TYPE_CHECK, null);
    }

    private static ValidationCheck pass(String id) {
        return new ValidationCheck(id, ValidationCheck.Outcome.PASS, true, "ok");
    }

    private ValidationReport report(ValidationOperation operation, boolean passed, List<ValidationCheck> checks) {
        return report(operation, passed, checks, List.of());
    }

    private ValidationReport report(ValidationOperation operation, boolean passed, List<ValidationCheck> checks,
            List<ProjectDiagnostic> diagnostics) {
        return new ValidationReport(operation, true, passed, passed, false, passed ? 0 : 1, "npm", "script", diagnostics,
                List.of(), 0, 2, revision, passed ? "ok" : "failed", checks);
    }
}
