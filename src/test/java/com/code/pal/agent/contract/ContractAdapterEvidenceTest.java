package com.code.pal.agent.contract;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.ContractBundleValidation;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contract-side evidence obligations of the S6 adapter; no commands, backend or providers are started. */
class ContractAdapterEvidenceTest {
    @TempDir
    Path workspace;
    private final UUID project = UUID.randomUUID();
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final ProjectValidationService validation = mock(ProjectValidationService.class);
    private final ContractStore store = mock(ContractStore.class);
    private final RunBehaviorCriteria criteria = mock(RunBehaviorCriteria.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final AgentContext ctx = mock(AgentContext.class);
    private final AgentRun run = mock(AgentRun.class);
    private final BuildContract contract = mock(BuildContract.class);
    private HostProjectFiles files;
    private ContractBundleValidation adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void open() {
        var props = new AgentProperties();
        props.setWorkspaceBasePath(workspace.toString());
        files = new HostProjectFiles(new WorkspaceStorage(props));
        files.writeText(project, "stage", "index.html", "<h1>Original</h1>");
        when(ctx.projectId()).thenReturn(project);
        UUID runId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        when(run.getId()).thenReturn(runId);
        when(contract.id()).thenReturn(contractId);
        when(contract.planRevision()).thenReturn(1L);
        var view = new ChangeSetService.WorkspaceView(".", "stage", "c", 3, List.of());
        when(changes.view(ctx)).thenReturn(view);
        var binding = new ContractStore.Binding(1, UUID.randomUUID(), 1, "code", "c".repeat(64), Set.of());
        when(store.current(project)).thenReturn(binding);
        when(validation.plannedCommand(ctx, ValidationOperation.TYPE_CHECK)).thenReturn(null);
        when(validation.plannedCommand(ctx, ValidationOperation.BUILD)).thenReturn(List.of("trusted-build"));
        when(validation.run(eq(ctx), eq(ValidationOperation.BUILD), isNull())).thenReturn(passed(3));
        when(leases.guarded(eq(project), any(Supplier.class))).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(leases).guarded(eq(project), any(Runnable.class));
        adapter = new ContractBundleValidation(validation, changes, files, store, criteria, leases, new ObjectMapper());
    }

    @Test
    void compilerReportForAnotherRevisionCannotBecomeABoundReceipt() {
        when(validation.run(eq(ctx), eq(ValidationOperation.BUILD), isNull())).thenReturn(passed(4));
        assertThrows(RuntimeException.class, () -> adapter.validateBundle(ctx, run, contract, "app", Set.of()));
    }

    @Test
    void changedSourceMakesBuildEvidenceUnavailableEvenWithoutARevisionBump() {
        when(validation.run(eq(ctx), eq(ValidationOperation.BUILD), isNull())).thenAnswer(call -> {
            files.writeText(project, "stage", "index.html", "<h1>Changed while compiling</h1>");
            return passed(3);
        });
        var bundle = adapter.validateBundle(ctx, run, contract, "app", Set.of());
        assertFalse(bundle.certifyEligible());
        assertTrue(bundle.checks().stream().noneMatch(check -> check.id().equals("BUILD")
                && check.outcome() == ValidationCheck.Outcome.PASS));
    }

    private ValidationReport passed(int revision) {
        return new ValidationReport(ValidationOperation.BUILD, true, true, true, false, 0, "trusted", "fixture",
                List.of(), List.of(), 0, 0, revision, "Passed", List.of());
    }
}
