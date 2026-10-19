package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeFeatureOutcomesTest {
    private final UUID project = UUID.randomUUID();
    private final ProjectFiles files = mock(ProjectFiles.class);
    private final AgentContext context = new AgentContext(project, 7L, ProjectType.BUILD, "stage", files);
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final SandboxPort sandbox = mock(SandboxPort.class);
    private final AgentProperties properties = new AgentProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final RuntimeCheckService runtime;
    private static final String DRAFT = "{\"features\":[{\"id\":\"contact\",\"kind\":\"CONTACT_FORM\"}]}";

    RuntimeFeatureOutcomesTest() {
        properties.getFeatureOutcomes().setEnabled(true);
        var decisions = mock(BuildDecisions.class);
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(
                1, BuildDecisions.Storage.NONE, BuildDecisions.Auth.NONE, null), new BuildDecisions.Available(true, null), null));
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(), 1, "{}", "Approved", DRAFT));
        when(changes.createValidationCandidate(context, "runtime")).thenReturn("candidate");
        when(files.regularFile(project, "candidate", "index.html")).thenReturn(true);
        runtime = new RuntimeCheckService(changes, new StackPacks(mapper), files, sandbox,
                mock(DependencyService.class), properties, mapper, BuildVariables.NONE);
        runtime.useFeatureOutcomes(new TrustedFeatureOutcomes(memory, decisions, properties, mapper));
    }

    private void report(String outcome) {
        when(sandbox.browse(eq(project), eq("candidate"), anyString())).thenAnswer(call -> {
            var requested = mapper.readTree(call.getArgument(2, String.class));
            assertEquals(1, requested.path("outcomes").size());
            assertEquals("FORM_FEEDBACK", requested.path("outcomes").get(0).path("kind").asString());
            assertFalse(requested.has("fixture"));
            return new SandboxPort.CommandResult(true, 0,
                    "{\"loaded\":true,\"issues\":[],\"interactions\":[],\"outcomes\":[{\"id\":\"contact\",\"outcome\":\"" + outcome + "\"}]}", "");
        });
    }

    @Test
    void passingPageSmokeCannotMaskAFailedIndependentFeatureOutcome() {
        report("FAIL");
        var result = runtime.evaluate(context);
        assertEquals(ValidationCheck.Outcome.PASS, result.check().outcome());
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("FEATURE_contact") && check.blocksSuccess()));
        var report = new ValidationReport(ValidationOperation.ALL, true, true, true, false, 0, "static", "",
                result.diagnostics(), List.of(), 0, 3, 1, "Fixture page loaded", result.checks());
        assertFalse(report.changeValidationPassed());
        verify(sandbox, never()).browse(any(), anyString());
        verify(sandbox).removeTree(project, "candidate");
    }

    @Test
    void independentPassRequiresItsExactProbeEvidenceAndIsNotAClaimOfDelivery() {
        report("PASS");
        var result = runtime.evaluate(context);
        var feature = result.checks().stream().filter(check -> check.id().equals("FEATURE_contact")).findFirst().orElseThrow();
        assertTrue(feature.required());
        assertFalse(feature.blocksSuccess());
        assertTrue(feature.reason().contains("delivery is not certified"));
    }

    @Test
    void aLegacyProbeWithoutOutcomeCoverageCannotPassTheNewRequiredTemplate() {
        when(sandbox.browse(eq(project), eq("candidate"), anyString()))
                .thenReturn(new SandboxPort.CommandResult(true, 0, "{\"loaded\":true,\"issues\":[],\"interactions\":[]}", ""));
        var result = runtime.evaluate(context);
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("FEATURE_contact") && check.unavailable()));
    }

    @Test
    void changedApprovedScopeDuringTheBrowserRunInvalidatesItsFeatureReceipt() {
        report("PASS");
        var current = memory.current(project);
        when(memory.current(project)).thenReturn(current,
                new ApprovedProjectMemory.Approved(UUID.randomUUID(), 2, "{}", "Changed", DRAFT));
        assertTrue(runtime.evaluate(context).checks().stream().anyMatch(check -> check.id().equals("FEATURE_APPROVAL") && check.unavailable()));
    }
}
