package com.code.pal.agent.stack;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.ValidationOperation;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.agent.workspace.HostProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.workspace.WorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractStackFixesTest {
    @TempDir
    Path workspace;
    private final UUID project = UUID.randomUUID();
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final DependencyService dependencies = mock(DependencyService.class);
    private final ProjectLeaseService leases = mock(ProjectLeaseService.class);
    private final AgentRun run = mock(AgentRun.class);
    private final BuildContract contract = mock(BuildContract.class);
    private final AgentContext ctx = mock(AgentContext.class);
    private HostProjectFiles files;
    private ContractStackFixes fixes;
    private int revision = 2;

    @BeforeEach
    void open() {
        var props = new AgentProperties();
        props.setWorkspaceBasePath(workspace.toString());
        files = new HostProjectFiles(new WorkspaceStorage(props));
        when(ctx.projectId()).thenReturn(project);
        when(run.getId()).thenReturn(UUID.randomUUID());
        when(run.getProjectId()).thenReturn(project);
        when(run.getRequestFingerprint()).thenReturn("request");
        when(contract.schemaVersion()).thenReturn(2);
        when(contract.stack()).thenReturn(BuildContract.Stack.VITE);
        when(contract.id()).thenReturn(UUID.randomUUID());
        when(changes.view(ctx)).thenAnswer(call -> new ChangeSetService.WorkspaceView(".", "stage", "c", revision, List.of()));
        doAnswer(call -> { revision++; return null; }).when(changes).replaceBatch(eq(ctx), anyList());
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; }).when(leases).guarded(eq(project), any(Runnable.class));
        fixes = new ContractStackFixes(changes, files, dependencies, leases, mock(SandboxPort.class), new ObjectMapper());
    }

    @Test
    void aMissingPreviewEntrypointIsRestoredFromTheTrustedStarterWithoutAModel() {
        files.writeText(project, "stage", "src/main.tsx", "import App from './App'");

        assertTrue(fixes.tryFix(ctx, run, contract, failure()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChangeSetService.Replacement>> batch = ArgumentCaptor.forClass(List.class);
        verify(changes).replaceBatch(eq(ctx), batch.capture());
        assertEquals(List.of("index.html"), batch.getValue().stream().map(ChangeSetService.Replacement::path).toList());
        assertEquals(files.version(project, "stage", "index.html"), batch.getValue().getFirst().expectedVersion());
        assertFalse(fixes.tryFix(ctx, run, contract, failure()), "the same defect is attempted once");
    }

    @Test
    void ordinaryCodeErrorsGoToTheModelRepair() {
        files.writeText(project, "stage", "index.html", "<div id=root></div>");
        var typeError = new ProjectDiagnostic("src/App.tsx", 1, 1, DiagnosticSeverity.ERROR, "Type error", "typecheck", "TS2304");
        assertFalse(fixes.tryFix(ctx, run, contract, failure(typeError)));
        verify(changes, never()).replaceBatch(any(), anyList());
    }

    @Test
    void installErrorsAreClassifiedOnlyFromTheirEvidence() {
        var etarget = new ProjectDiagnostic("package.json", null, null, DiagnosticSeverity.ERROR,
                "npm error code ETARGET No matching version found for vite@9.9", "dependencies", "DEPENDENCY_INSTALL_FAILED");
        var lock = new ProjectDiagnostic("package.json", null, null, DiagnosticSeverity.ERROR,
                "npm ci can only install packages when your package.json and package-lock.json are in sync",
                "dependencies", "DEPENDENCY_INSTALL_FAILED");
        var typed = new ProjectDiagnostic("src/a.ts", 1, 1, DiagnosticSeverity.ERROR, "ETARGET mentioned in a string", "typecheck", "TS1");
        Map<String, String> snapshot = Map.of("index.html", "x");
        assertEquals(Set.of("DEPENDENCY_VERSION_UNAVAILABLE", "LOCKFILE_MISMATCH"),
                ContractStackFixes.defects(failure(etarget, lock, typed), snapshot));
        assertEquals(Set.of(), ContractStackFixes.defects(failure(typed), snapshot));
    }

    private ValidationReport failure(ProjectDiagnostic... diagnostics) {
        return new ValidationReport(ValidationOperation.BUILD, true, false, false, false, 1, "npm", "build",
                List.of(diagnostics), List.of(), 0, 2, revision, "failed");
    }
}
