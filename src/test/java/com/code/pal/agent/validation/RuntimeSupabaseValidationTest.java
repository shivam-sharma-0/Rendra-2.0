package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.StackPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.agent.workspace.SupabaseScratch;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeSupabaseValidationTest {
    private static final String REPORT = "{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[]}";
    private final UUID project = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectFiles files = mock(ProjectFiles.class);
    private final AgentContext context = new AgentContext(project, 8L, ProjectType.BUILD, "stage", files);
    private final ChangeSetService changes = mock(ChangeSetService.class);
    private final SandboxPort sandbox = mock(SandboxPort.class);
    private final BuildVariables liveVariables = mock(BuildVariables.class);
    private final SupabaseScratch factory = mock(SupabaseScratch.class);
    private final SupabaseScratch.Session session = mock(SupabaseScratch.Session.class);
    private final StackPack stack = mock(StackPack.class);
    private final AgentProperties properties = new AgentProperties();
    private final RuntimeCheckService runtime;
    private final Map<String, String> isolatedVariables = Map.of(
            "VITE_SUPABASE_URL", "http://codepal-supabase.invalid", "VITE_SUPABASE_ANON_KEY", "fresh-public-key");
    private final SandboxPort.ServerEndpoint endpoint = new SandboxPort.ServerEndpoint("http://127.0.0.1:40000", Map.of());

    @SuppressWarnings("unchecked")
    RuntimeSupabaseValidationTest() {
        var stacks = mock(StackPacks.class);
        var dependencies = mock(DependencyService.class);
        var decisions = mock(BuildDecisions.class);
        var memory = mock(ApprovedProjectMemory.class);
        var api = mock(SupabaseApiAcceptance.class);
        ObjectProvider<SupabaseScratch> provider = mock(ObjectProvider.class);
        properties.getSupabaseValidation().setEnabled(true);
        when(provider.getIfAvailable()).thenReturn(factory);
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(
                1, BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.ACCOUNTS, "connection-revision"),
                new BuildDecisions.Available(true, "connection-revision"), null));
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(), 1, "{}", "Approved", """
                {"entities":[{"id":"tasks","ownership":"USER","fields":[{"name":"title","kind":"STRING","required":true}]}]}
                """));
        byte[] sql = "CREATE TABLE tasks(id uuid);".getBytes(StandardCharsets.UTF_8);
        when(files.list(project, "candidate/supabase/migrations", 2)).thenReturn(List.of(new ProjectFiles.Entry("001.sql", false, sql.length)));
        when(files.readBytes(eq(project), eq("candidate/supabase/migrations"), eq("001.sql"), anyInt())).thenReturn(sql);
        when(factory.open(eq(project), eq(context.runId()), anyList())).thenReturn(session);
        when(session.endpoint()).thenReturn(endpoint);
        when(session.buildVariables()).thenReturn(isolatedVariables);
        when(api.check(eq(session), anyList(), eq(true))).thenReturn(List.of(
                new ValidationCheck("SUPABASE_AUTH", ValidationCheck.Outcome.PASS, true, "Independent fixture auth evidence")));
        when(changes.createValidationCandidate(context, "runtime")).thenReturn("candidate");
        when(stacks.detect(eq(files), eq(project), anyString())).thenReturn(stack);
        when(stack.id()).thenReturn("node");
        when(stack.diagnosticBuild(files, project, "candidate")).thenReturn(new StackPack.PreviewBuild(List.of("trusted-build"), List.of("dist")));
        when(dependencies.prepare(project, "candidate")).thenReturn(new DependencyService.Preparation(true, false, null));
        when(sandbox.exec(eq(project), eq("candidate"), anyList())).thenReturn(new SandboxPort.CommandResult(true, 0, "", ""));
        when(files.regularFile(project, "candidate/dist", "index.html")).thenReturn(true);
        when(sandbox.browseWithSupabase(eq(project), eq("candidate/dist"), anyString(), eq(endpoint)))
                .thenReturn(new SandboxPort.CommandResult(true, 0, REPORT, ""));
        runtime = new RuntimeCheckService(changes, stacks, files, sandbox, dependencies, properties, mapper, liveVariables);
        runtime.useSupabaseValidation(new CandidateSupabaseValidation(decisions, memory, files, properties, mapper, provider, api));
    }

    @Test
    void buildAndOfflineBrowserUseOnlyTheSameScratchTargetAndAlwaysCleanIt() {
        var result = runtime.evaluate(context);
        assertTrue(result.checks().stream().noneMatch(ValidationCheck::blocksSuccess));
        verifyNoInteractions(liveVariables);
        verify(stack).applyBuildVariables(files, project, "candidate", isolatedVariables);
        verify(sandbox).browseWithSupabase(eq(project), eq("candidate/dist"), anyString(), eq(endpoint));
        verify(sandbox, never()).browse(any(), anyString());
        verify(factory, times(1)).open(eq(project), eq(context.runId()), anyList());
        verify(session).close();
        verify(sandbox).removeTree(project, "candidate");
    }

    @Test
    void cloudRecipeRunsBeforeGenericMutationsAndNeverLoadsALiveBaseline() {
        var criteria = mock(RunBehaviorCriteria.class);
        when(criteria.current(context)).thenReturn(new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction(
                "saved_0", BehaviorCriteria.BrowserKind.ASSERT_TEXT, "#result", "Saved")), List.of(), false, false,
                BehaviorCriteria.Provenance.MODEL_SPEC));
        runtime.useBehaviorCriteria(criteria);
        when(sandbox.browseWithSupabase(eq(project), eq("candidate/dist"), anyString(), eq(endpoint))).thenAnswer(invocation -> {
            var actions = mapper.readTree(invocation.getArgument(2, String.class)).path("actions");
            String report = actions.isEmpty()
                    ? "{\"loaded\":true,\"issues\":[{\"kind\":\"exception\",\"message\":\"Generic action failed\"}],\"interactions\":[],\"acceptance\":[]}"
                    : REPORT.replace("\"acceptance\":[]", "\"acceptance\":[{\"id\":\"saved_0\",\"passed\":true}]");
            return new SandboxPort.CommandResult(true, 0, report, "");
        });
        var result = runtime.evaluate(context);
        assertEquals(ValidationCheck.Outcome.FAIL, result.check().outcome());
        assertEquals(ValidationCheck.Outcome.PASS, result.approvedBehaviour().outcome());
        var calls = ArgumentCaptor.forClass(String.class);
        verify(sandbox, times(2)).browseWithSupabase(eq(project), eq("candidate/dist"), calls.capture(), eq(endpoint));
        assertEquals(1, mapper.readTree(calls.getAllValues().get(0)).path("actions").size());
        assertTrue(mapper.readTree(calls.getAllValues().get(1)).path("actions").isEmpty());
        verify(changes, never()).createValidationBaseline(any(), anyString());
        verifyNoInteractions(liveVariables);
        verify(session).close();
    }

    @Test
    void disabledAdapterReturnsRequiredSkipWithoutReadingLiveVariablesOrStartingABuild() {
        properties.getSupabaseValidation().setEnabled(false);
        var result = runtime.evaluate(context);
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("SUPABASE_BEHAVIOUR") && check.unavailable()));
        verifyNoInteractions(factory, liveVariables);
        verify(sandbox, never()).exec(any(), anyString(), anyList());
        verify(sandbox, never()).browseWithSupabase(any(), anyString(), anyString(), any());
        verify(sandbox).removeTree(project, "candidate");
    }

    @Test
    void globallyDisabledRuntimeDoesNotSilentlyCertifyCloudData() {
        properties.getSandbox().setRuntimeCheck(false);
        var result = runtime.evaluate(context);
        assertFalse(result.check().required());
        assertTrue(result.checks().stream().anyMatch(ValidationCheck::unavailable));
        verifyNoInteractions(changes, factory, liveVariables);
    }
}
