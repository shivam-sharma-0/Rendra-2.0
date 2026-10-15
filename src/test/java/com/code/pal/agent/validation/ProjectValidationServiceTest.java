package com.code.pal.agent.validation;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProjectValidationServiceTest {
    /** Real (local) dependency handling unless a test replaces it. */
    private com.code.pal.agent.stack.DependencyService dependencies;
    /** No browser unless a test supplies one. */
    private RuntimeCheckService runtime;
    private com.code.pal.agent.stack.StackPacks stackOverride;
    /** Every type-check execution (baseline or candidate) appends one line here. */
    private final java.nio.file.Path executions = newExecutionLog();

    private static java.nio.file.Path newExecutionLog() {
        try {
            return java.nio.file.Files.createTempFile("codepal-validation-runs", ".log");
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private int executionCount() throws Exception {
        return java.nio.file.Files.readAllLines(executions).size();
    }
    @Test void aRuntimeFailureDefersApiChecksWithoutFalselyEndingCodeRepair() throws Exception {
        runtime=mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.FAIL,true,"UI failed"),List.of(new ProjectDiagnostic(null,null,null,DiagnosticSeverity.ERROR,"Missing task row","runtime","BROWSER_ACCEPTANCE_FAILED"))));
        var f=fixture(3);var criteria=mock(RunBehaviorCriteria.class);
        when(criteria.current(any(AgentContext.class))).thenReturn(new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("ui",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#root","Items")),List.of(new BehaviorCriteria.ApiStep("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",null,200,null,null,null,null,null)),false,false));
        f.validation.useBehaviorCriteria(criteria);f.changeSets.create(f.context,"src/feature.ts","export const feature = true;\n");
        var report=f.validation.validateAll(f.context);assertFalse(report.changeValidationPassed());
        var deferred=report.checks().stream().filter(check->check.id().equals("API_health")).findFirst().orElseThrow();assertEquals(ValidationCheck.SkipCause.PREREQUISITE_FAILED,deferred.skipCause());assertFalse(deferred.unavailable());
        assertFalse(f.changeSets.validationProgress(f.context).repairLimitExhausted());
        assertDoesNotThrow(()->f.changeSets.patch(f.context,"src/App.tsx",f.version(),"OK","BETTER"));
    }
    @Test void aRequiredSkippedRuntimeProbeBlocksPromotionWithoutModelRepair() throws Exception {
        runtime=mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,true,"No browser available"),List.of()));
        var f=fixture(3);f.changeSets.create(f.context,"src/extra.ts","export const value=1;");
        var report=f.validation.validateAll(f.context);
        assertFalse(report.changeValidationPassed());assertEquals(ValidationCheck.Outcome.SKIPPED,report.outcome());
        assertEquals("REQUIRED_CHECK_SKIPPED",report.introducedDiagnostics().getFirst().code());
        assertTrue(f.changeSets.validationProgress(f.context).repairLimitExhausted());
        assertTrue(f.changeSets.hasUnvalidatedChanges(f.context));
    }
    @Test void failingUiStillChecksIndependentApiAndDoesNotBlamePassingBackend() throws Exception {
        runtime=mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.FAIL,true,"UI failed"),List.of(new ProjectDiagnostic(null,null,null,DiagnosticSeverity.ERROR,"Missing task row","runtime","BROWSER_ACCEPTANCE_FAILED"))));
        // Keep real trusted Node compiler commands, selecting the MERN API branch independently.
        var node=spy(new com.code.pal.agent.stack.NodePack(new ObjectMapper()));doReturn("mern").when(node).id();
        stackOverride=new com.code.pal.agent.stack.StackPacks(List.of(node));
        var fixture=fixture(3);var api=mock(MernBehaviorCheck.class);
        when(api.check(fixture.context)).thenReturn(List.of(new ValidationCheck("API_health",ValidationCheck.Outcome.PASS,true,"Healthy")));
        fixture.validation.useMernBehavior(api);fixture.changeSets.create(fixture.context,"src/feature.ts","export const feature=true;");
        var report=fixture.validation.validateAll(fixture.context);
        verify(api).check(fixture.context);assertFalse(report.changeValidationPassed());
        assertTrue(report.checks().stream().anyMatch(c->c.id().equals("API_health") && c.outcome()==ValidationCheck.Outcome.PASS));
        assertEquals(List.of("BROWSER_ACCEPTANCE_FAILED"),report.introducedDiagnostics().stream().map(ProjectDiagnostic::code).toList());
        assertFalse(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted());
        assertTrue(fixture.changeSets.hasUnvalidatedChanges(fixture.context));
    }
    @Test void unavailableBrowserDoesNotStartIndependentApiOrSpendCodeRepairs() throws Exception {
        runtime=mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,true,"Browser unavailable"),List.of()));
        var node=spy(new com.code.pal.agent.stack.NodePack(new ObjectMapper()));doReturn("mern").when(node).id();
        stackOverride=new com.code.pal.agent.stack.StackPacks(List.of(node));
        var fixture=fixture(3);var api=mock(MernBehaviorCheck.class);fixture.validation.useMernBehavior(api);
        fixture.changeSets.create(fixture.context,"src/feature.ts","export const feature=true;");
        assertFalse(fixture.validation.validateAll(fixture.context).changeValidationPassed());verifyNoInteractions(api);
        assertTrue(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted());
    }
    @Test void installedAcceptanceCannotDisappearWhenRuntimeIsDisabled() throws Exception {
        runtime=mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,false,"disabled"),List.of()));
        var f=fixture(3);f.changeSets.create(f.context,"src/extra.ts","export const value=1;");
        var criteria=mock(RunBehaviorCriteria.class);when(criteria.current(any(AgentContext.class))).thenReturn(new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("ui",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#root","Items")),List.of(),false,false));f.validation.useBehaviorCriteria(criteria);
        var report=f.validation.validateAll(f.context);assertFalse(report.changeValidationPassed());assertTrue(report.checks().stream().anyMatch(c->c.id().equals("BROWSER_ACCEPTANCE") && c.unavailable()));
        assertTrue(f.changeSets.hasUnvalidatedChanges(f.context));assertTrue(f.changeSets.validationProgress(f.context).repairLimitExhausted());
    }

    @Test void aCapabilityMismatchBlocksCertificationBeforeCommandsRun() throws Exception {
        Fixture fixture = fixture(3);
        var decisions = mock(com.code.pal.project.capability.BuildDecisions.class);
        when(decisions.validate(eq(fixture.context.projectId()), any(), eq(fixture.context.root())))
                .thenReturn("Supabase disconnected; confirm storage choice");
        fixture.validation.setBuildDecisions(decisions);
        var report = fixture.validation.validateAll(fixture.context);
        assertFalse(report.changeValidationPassed());
        assertEquals("BUILD_DECISION_MISMATCH", report.introducedDiagnostics().getFirst().code());
        assertEquals(0, executionCount());
    }

    @Test
    void theFinalGateReusesResultsTheAgentAlreadyRanForTheSameRevision() throws Exception {
        runtime = mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.PASS,true,"Fixture passed"),List.of()));
        Fixture fixture = fixture(3);
        fixture.changeSets.create(fixture.context, "src/extra.ts", "export const x = 1;\n");

        assertTrue(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());
        assertEquals(2, executionCount(), "baseline + candidate");
        assertTrue(fixture.changeSets.hasUnvalidatedChanges(fixture.context), "a single operation does not certify");

        ValidationReport gate = fixture.validation.validateAll(fixture.context);
        assertTrue(gate.changeValidationPassed());
        assertEquals(2, executionCount(), "same revision: nothing runs again");
        assertFalse(fixture.changeSets.hasUnvalidatedChanges(fixture.context));
        fixture.validation.validateAll(fixture.context);
        verify(runtime, times(1)).evaluate(any());

        fixture.changeSets.create(fixture.context, "src/more.ts", "export const y = 2;\n");
        assertTrue(fixture.validation.validateAll(fixture.context).changeValidationPassed());
        assertEquals(4, executionCount(), "a new revision is validated again");
        verify(runtime, times(2)).evaluate(any());
    }

    @Test
    void aFailedResultIsReusedUntilTheAgentChangesSomething() throws Exception {
        Fixture fixture = fixture(3);
        fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(), "OK", "BROKEN");

        assertFalse(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());
        ValidationReport gate = fixture.validation.validateAll(fixture.context);

        assertFalse(gate.changeValidationPassed());
        assertEquals("src/App.tsx", gate.introducedDiagnostics().getFirst().file());
        assertEquals(2, executionCount());
    }

    @Test
    void aRuntimeErrorTheChangeIntroducedFailsTheFinalGate() throws Exception {
        runtime = mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.FAIL,true,"Fixture failed"),List.of(new ProjectDiagnostic(null, null, null, DiagnosticSeverity.ERROR,
                "TypeError: Cannot read properties of undefined (reading 'map') (at assets/index.js:40:12 (App))", "runtime", "RUNTIME_EXCEPTION"))));
        Fixture fixture = fixture(3);
        fixture.changeSets.create(fixture.context, "src/extra.ts", "export const x = 1;\n");

        ValidationReport report = fixture.validation.validateAll(fixture.context);

        assertFalse(report.changeValidationPassed());
        assertEquals("RUNTIME_EXCEPTION", report.introducedDiagnostics().getFirst().code());
        assertFalse(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted(), "the model repairs it");
    }

    @Test
    void aCleanRuntimeCheckLetsTheChangePass() throws Exception {
        runtime = mock(RuntimeCheckService.class);
        when(runtime.evaluate(any())).thenReturn(new RuntimeCheckService.Result(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.PASS,true,"Fixture passed"),List.of()));
        Fixture fixture = fixture(3);
        fixture.changeSets.create(fixture.context, "src/extra.ts", "export const x = 1;\n");

        ValidationReport report = fixture.validation.validateAll(fixture.context);

        assertTrue(report.changeValidationPassed());
        assertTrue(report.summary().contains("RUNTIME"), report.summary());
    }


    @Test
    void aDependencyInstallTheChangeBrokeIsReportedAsRepairable() throws Exception {
        dependencies = mock(com.code.pal.agent.stack.DependencyService.class);
        when(dependencies.prepare(any(), anyString())).thenAnswer(call -> call.<String>getArgument(1).contains("/candidate-")
                ? new com.code.pal.agent.stack.DependencyService.Preparation(true, false, "Dependencies could not be installed: npm ERR! 404 nope@9")
                : new com.code.pal.agent.stack.DependencyService.Preparation(true, false, null));
        Fixture fixture = fixture(3);
        fixture.changeSets.create(fixture.context, "src/extra.ts", "export const x = 1;\n");

        ValidationReport report = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertFalse(report.changeValidationPassed());
        assertEquals("DEPENDENCY_INSTALL_FAILED", report.introducedDiagnostics().getFirst().code());
        assertTrue(report.introducedDiagnostics().getFirst().message().contains("nope@9"));
        assertFalse(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted(), "the model can fix package.json");
    }

    @Test
    void whenNothingInstallsTheChangeIsNotBlamedAndRepairStops() throws Exception {
        dependencies = mock(com.code.pal.agent.stack.DependencyService.class);
        when(dependencies.prepare(any(), anyString())).thenReturn(
                new com.code.pal.agent.stack.DependencyService.Preparation(true, false, "Dependencies could not be installed: ENOTFOUND registry"));
        Fixture fixture = fixture(3);
        fixture.changeSets.create(fixture.context, "src/extra.ts", "export const x = 1;\n");

        ValidationReport report = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertFalse(report.changeValidationPassed());
        assertTrue(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted(),
                "an infrastructure failure must not send the model into repair loops");
    }

    @Test
    void reportsIntroducedDiagnosticThenAcceptsBoundedRepair() throws Exception {
        Fixture fixture = fixture(3);
        String initial = fixture.version();
        fixture.changeSets.patch(fixture.context, "src/App.tsx", initial, "OK", "BROKEN");

        ValidationReport failed = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertFalse(failed.changeValidationPassed());
        assertEquals("src/App.tsx", failed.introducedDiagnostics().getFirst().file());
        assertEquals(1, failed.introducedDiagnostics().getFirst().line());
        assertTrue(failed.preExistingDiagnostics().isEmpty());

        fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(),
                "BROKEN", "OK");
        ValidationReport repaired = fixture.validation.validateAll(fixture.context);

        assertTrue(repaired.changeValidationPassed());
        assertEquals(1, repaired.repairAttempts());
        assertFalse(fixture.changeSets.hasUnvalidatedChanges(fixture.context));
        assertEquals("export const value = 'OK';\n", fixture.project().read("src/App.tsx"),
                "validation and repair must remain isolated from the live workspace");
    }

    @Test
    void refusesARepairBeyondConfiguredLimit() throws Exception {
        Fixture fixture = fixture(2);
        fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(), "OK", "BROKEN-0");
        assertFalse(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());

        for (int attempt = 1; attempt <= 2; attempt++) {
            String previous = "BROKEN-" + (attempt - 1);
            String next = "BROKEN-" + attempt;
            fixture.changeSets.patch(fixture.context, "src/App.tsx", fixture.version(), previous, next);
            assertFalse(fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null).changeValidationPassed());
        }

        assertTrue(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted());
        AgentToolException error = assertThrows(AgentToolException.class, () -> fixture.changeSets.patch(
                fixture.context, "src/App.tsx", fixture.version(), "BROKEN-2", "OK"));
        assertEquals("REPAIR_LIMIT_EXCEEDED", error.code());
    }

    @Test
    void distinguishesUnrelatedPreExistingDiagnostics() throws Exception {
        Fixture fixture = fixture(3, "BROKEN-existing");
        fixture.changeSets.create(fixture.context, "src/theme.ts", "export const dark = true;\n");

        ValidationReport report = fixture.validation.run(fixture.context, ValidationOperation.TYPE_CHECK, null);

        assertTrue(report.changeValidationPassed());
        assertTrue(report.introducedDiagnostics().isEmpty());
        assertFalse(report.preExistingDiagnostics().isEmpty());
    }
    @Test void unchangedExistingCompilerFailureCannotCertifyTheFinalBuild() throws Exception {
        var fixture=fixture(3,"BROKEN-existing");fixture.changeSets.create(fixture.context,"src/theme.ts","export const theme=true;");
        var report=fixture.validation.validateAll(fixture.context);
        assertFalse(report.changeValidationPassed());assertTrue(report.checks().stream().anyMatch(c->c.id().equals("TYPE_CHECK") && c.outcome()==ValidationCheck.Outcome.FAIL));
        assertTrue(report.introducedDiagnostics().stream().anyMatch(d->"PRE_EXISTING_VALIDATION_FAILURE".equals(d.code())));
        assertTrue(fixture.changeSets.hasUnvalidatedChanges(fixture.context));assertTrue(fixture.changeSets.validationProgress(fixture.context).repairLimitExhausted());
    }

    private Fixture fixture(int maxRepairs) throws Exception {
        return fixture(maxRepairs, "OK");
    }

    private Fixture fixture(int maxRepairs, String initialValue) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxRepairAttempts(maxRepairs);
        properties.getSandbox().setRuntimeCheck(runtime != null); // Command-only fixtures explicitly opt out.
        properties.getSandbox().setCommandTimeoutMs(15_000);
        LocalProject project = LocalProject.create(properties)
                .write("src/App.tsx", "export const value = '" + initialValue + "';\n")
                .write("package.json", """
                        {"packageManager":"npm@10.9.0","scripts":{"typecheck":"node validate.cjs"}}
                        """)
                .write("package-lock.json", "{\"lockfileVersion\":3}")
                .write("validate.cjs", """
                        const fs = require('fs');
                        fs.appendFileSync('%s', 'run\\n');
                        const value = fs.readFileSync('src/App.tsx', 'utf8');
                        if (value.includes('BROKEN')) {
                          console.error('src/App.tsx(1,1): error TS2307: Cannot find module theme');
                          process.exit(1);
                        }
                        """.formatted(executions.toString().replace('\\', '/')));

        FileChangeRepository repository = mock(FileChangeRepository.class);
        when(repository.save(any(FileChange.class))).thenAnswer(call -> call.getArgument(0));
        ObjectMapper mapper = new ObjectMapper();
        ChangeSetService changeSets = new ChangeSetService(repository, mock(AgentRunRepository.class), mapper,
                properties, project.files, project.sandbox, mock(com.code.pal.project.service.ProjectService.class));
        UUID runId = UUID.randomUUID();
        String stage = changeSets.start(runId, project.projectId);
        AgentPlanState plan = new AgentPlanState();
        plan.revise(List.of("Modify App", "Type-check", "Repair if necessary"), "test");
        AgentContext context = new AgentContext(project.projectId, 7L, ProjectType.BUILD, stage, null,
                new ProjectContextTrace(), runId, plan, project.files);
        var stacks = stackOverride != null ? stackOverride : new com.code.pal.agent.stack.StackPacks(mapper);
        ProjectValidationService validation = new ProjectValidationService(changeSets, stacks, properties, project.files,
                project.sandbox, dependencies != null ? dependencies
                        : new com.code.pal.agent.stack.DependencyService(stacks, project.files, project.sandbox, properties), runtime);
        return new Fixture(project, context, changeSets, validation);
    }

    private record Fixture(LocalProject project, AgentContext context, ChangeSetService changeSets,
                           ProjectValidationService validation) {
        String version() {
            return project.files.version(project.projectId, context.root(), "src/App.tsx");
        }
    }
}
