package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ModelSpecRuntimeTest {
    private static final String CLEAN = "{\"loaded\":true,\"issues\":[],\"interactions\":[]}";
    private static final String FAILED = """
            {"loaded":true,"issues":[],"interactions":[],"acceptance":[
              {"id":"add_task_0","passed":false},
              {"id":"complete_task_0","passed":false,"prerequisiteFailed":true}]}
            """;
    private static final String PASSED = """
            {"loaded":true,"issues":[],"interactions":[],"acceptance":[
              {"id":"add_task_0","passed":true},{"id":"complete_task_0","passed":true}]}
            """;

    @Test
    void modelAssertionsBecomeAnAdvisoryCheckWithScenarioIdsAndWarningDiagnostics() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, FAILED);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertEquals(ValidationCheck.Outcome.PASS, result.check().outcome());
        var advisory = result.approvedBehaviour();
        assertEquals("APPROVED_BEHAVIOUR", advisory.id());
        assertEquals(ValidationCheck.Outcome.FAIL, advisory.outcome());
        assertFalse(advisory.required());
        assertFalse(advisory.blocksSuccess());
        assertEquals("MODEL_SPEC; failed_scenarios=add_task; blocked_scenarios=complete_task", advisory.reason());
        assertEquals(List.of(result.check(), advisory), result.checks());
        var report = new ValidationReport(ValidationOperation.ALL, true, true, true, false, 0, "fixture", "fixture",
                result.diagnostics(), List.of(), 0, 2, 1, "Built").withChecks(result.checks());
        assertTrue(report.changeValidationPassed());
        assertEquals(ValidationCheck.Outcome.PASS, report.outcome());
        assertEquals(List.of("MODEL_SPEC_ASSERTION_FAILED", "MODEL_SPEC_ASSERTION_BLOCKED"),
                result.diagnostics().stream().map(ProjectDiagnostic::code).toList());
        assertTrue(result.diagnostics().stream().allMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.WARNING));
        assertTrue(result.diagnostics().getFirst().message().contains("add_task_0"));
        verify(fixture.changes, never()).createValidationBaseline(any(), anyString());
    }

    @Test
    void modelChecksThatPassedStayVisibleAndSmokeRunsFirstOnTheSameBuiltCandidate() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, PASSED);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertEquals(ValidationCheck.Outcome.PASS, result.check().outcome());
        assertEquals(ValidationCheck.Outcome.PASS, result.approvedBehaviour().outcome());
        assertTrue(result.diagnostics().isEmpty());
        var order = inOrder(fixture.sandbox);
        order.verify(fixture.sandbox).browse(fixture.project, "candidate");
        order.verify(fixture.sandbox).browse(eq(fixture.project), eq("candidate"), contains("add_task_0"));
        verify(fixture.changes).createValidationCandidate(fixture.ctx, "runtime");
        verify(fixture.sandbox).removeTree(fixture.project, "candidate");
    }

    @Test
    void legacyAndPlatformAssertionsRemainRequired() {
        for (boolean explicit : List.of(false, true)) {
            var fixture = new Fixture(false, false);
            if (explicit) {
                var legacy = fixture.criteria.current(fixture.ctx);
                var platform = new BehaviorCriteria(1, legacy.browser(), List.of(), false, false, BehaviorCriteria.Provenance.PLATFORM);
                when(fixture.criteria.current(fixture.ctx)).thenReturn(platform);
            }
            fixture.reports(CLEAN, FAILED);
            var result = fixture.runtime.evaluate(fixture.ctx);
            assertTrue(result.check().blocksSuccess());
            assertNull(result.approvedBehaviour());
            assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("BROWSER_ACCEPTANCE_FAILED")));
        }
    }

    @Test
    void inheritedModelChecksKeepTheirProvenance() {
        var fixture = new Fixture(true, true);
        fixture.reports(CLEAN, FAILED);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertFalse(result.check().blocksSuccess());
        assertEquals(ValidationCheck.Outcome.FAIL, result.approvedBehaviour().outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception", "blank", "asset", "console", "acceptance"})
    void probeIssuesNeverBecomeAdvisoryBecauseOfModelProvenance(String kind) {
        var fixture = new Fixture(true, false);
        String broken = CLEAN.replace("\"issues\":[]", "\"issues\":[{\"kind\":\"" + kind + "\",\"message\":\"Broken\"}]");
        fixture.reports(broken, FAILED);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertTrue(result.check().blocksSuccess());
        assertEquals(ValidationCheck.Outcome.FAIL, result.approvedBehaviour().outcome());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR));
    }

    @Test
    void exceptionsDuringSpecActionsStillBlockEvenWhenTheAssertionsPass() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, PASSED.replace("\"issues\":[]", "\"issues\":[{\"kind\":\"exception\",\"message\":\"Crash\"}]"));
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertTrue(result.check().blocksSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("RUNTIME_EXCEPTION")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"error\":\"Browser crashed\"}", "{}",
            "{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[]}",
            "{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[{\"id\":\"wrong\",\"passed\":false}]}"})
    void crashedOrIncompleteSpecProbesRemainRequiredSkips(String report) {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, report);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertEquals(ValidationCheck.Outcome.SKIPPED, result.check().outcome());
        assertTrue(result.check().blocksSuccess());
        assertNull(result.approvedBehaviour());
    }

    @Test
    void anUnavailableSmokeProbeCannotBeBypassedByPassingModelChecks() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, PASSED);
        when(fixture.sandbox.browse(fixture.project, "candidate"))
                .thenReturn(new SandboxPort.CommandResult(false, -1, "", "Unavailable"));
        assertTrue(fixture.runtime.evaluate(fixture.ctx).check().blocksSuccess());
        verify(fixture.sandbox, never()).browse(eq(fixture.project), eq("candidate"), anyString());
    }

    @Test
    void anExplicitBlankFlagStillBlocksWithAnEmptyIssueList() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN.replace("\"loaded\":true", "\"loaded\":true,\"blank\":true"), PASSED);
        assertTrue(fixture.runtime.evaluate(fixture.ctx).check().blocksSuccess());
    }

    @Test
    void aPageThatGoesBlankDuringSpecActionsStillBlocks() {
        var fixture = new Fixture(true, false);
        fixture.reports(CLEAN, PASSED.replace("\"loaded\":true", "\"loaded\":true,\"blank\":true"));
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertTrue(result.check().blocksSuccess());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals("BLANK_PAGE")));
    }

    @Test
    void noModelCriteriaMeansNoExtraCheckAndExactlyOneSmokeProbe() {
        var fixture = new Fixture(false, false);
        when(fixture.criteria.current(fixture.ctx)).thenReturn(null);
        fixture.reports(CLEAN, PASSED);
        var result = fixture.runtime.evaluate(fixture.ctx);
        assertEquals(ValidationCheck.Outcome.PASS, result.check().outcome());
        assertNull(result.approvedBehaviour());
        assertEquals(List.of(result.check()), result.checks());
        verify(fixture.sandbox).browse(fixture.project, "candidate");
        verify(fixture.sandbox, never()).browse(any(), anyString(), anyString());
    }

    private static final class Fixture {
        final UUID project = UUID.randomUUID();
        final ChangeSetService changes = mock(ChangeSetService.class);
        final ProjectFiles files = mock(ProjectFiles.class);
        final SandboxPort sandbox = mock(SandboxPort.class);
        final RunBehaviorCriteria criteria = mock(RunBehaviorCriteria.class);
        final AgentContext ctx = new AgentContext(project, 7L, ProjectType.BUILD, "stage", files);
        final RuntimeCheckService runtime;

        Fixture(boolean model, boolean inherited) {
            var mapper = new ObjectMapper();
            var props = new AgentProperties();
            props.getSandbox().setRuntimeCheck(true);
            when(changes.createValidationCandidate(ctx, "runtime")).thenReturn("candidate");
            when(changes.createValidationBaseline(ctx, "runtime")).thenReturn("baseline");
            when(files.regularFile(eq(project), anyString(), eq("index.html"))).thenReturn(true);
            String provenance = model ? ",\"provenance\":\"MODEL_SPEC\"" : "";
            var installed = mapper.readValue("""
                    {"schema":1,"browser":[
                      {"id":"add_task_0","kind":"ASSERT_TEXT","selector":"#task","value":"Bread"},
                      {"id":"complete_task_0","kind":"ASSERT_COUNT","selector":"input:checked","value":"1"}],
                     "api":[],"authRequired":false,"crudRequired":false%s}
                    """.formatted(provenance), BehaviorCriteria.class);
            if (inherited) {
                when(criteria.regression(ctx)).thenReturn(installed);
            } else {
                when(criteria.current(ctx)).thenReturn(installed);
            }
            runtime = new RuntimeCheckService(changes, new StackPacks(mapper), files, sandbox,
                    mock(DependencyService.class), props, mapper, BuildVariables.NONE);
            runtime.useBehaviorCriteria(criteria);
        }

        void reports(String smoke, String acceptance) {
            var mapper = new ObjectMapper();
            String smokeJson = mapper.writeValueAsString(mapper.readTree(smoke));
            String acceptanceJson = mapper.writeValueAsString(mapper.readTree(acceptance));
            when(sandbox.browse(project, "candidate")).thenReturn(new SandboxPort.CommandResult(true, 0, smokeJson, ""));
            when(sandbox.browse(eq(project), eq("candidate"), anyString()))
                    .thenReturn(new SandboxPort.CommandResult(true, 0, acceptanceJson, ""));
            when(sandbox.browse(project, "baseline")).thenReturn(new SandboxPort.CommandResult(true, 0, CLEAN, ""));
        }
    }
}
