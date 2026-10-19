package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SupabaseScratch;
import com.code.pal.agent.workspace.SupabaseScratchException;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateSupabaseValidationTest {
    private static final String SCOPE = """
            {"entities":[{"id":"tasks","ownership":"USER","fields":[{"name":"title","kind":"STRING","required":true}]}],
             "requestedScope":{"entities":[
               {"id":"tasks","ownership":"USER","fields":[{"name":"title","kind":"STRING","required":true}]},
               {"id":"notes","ownership":"USER","fields":[{"name":"body","kind":"STRING","required":true}]}]}}
            """;
    private final UUID project = UUID.randomUUID();
    private final ProjectFiles files = mock(ProjectFiles.class);
    private final AgentContext context = new AgentContext(project, 9L, ProjectType.BUILD, "stage", files);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final AgentProperties properties = new AgentProperties();
    private final SupabaseScratch factory = mock(SupabaseScratch.class);
    private final SupabaseScratch.Session session = mock(SupabaseScratch.Session.class);
    private final SupabaseApiAcceptance api = mock(SupabaseApiAcceptance.class);
    private final ObjectProvider<SupabaseScratch> provider = provider();
    private final CandidateSupabaseValidation validation = new CandidateSupabaseValidation(
            decisions, memory, files, properties, new ObjectMapper(), provider, api);

    @SuppressWarnings("unchecked")
    private ObjectProvider<SupabaseScratch> provider() { return mock(ObjectProvider.class); }

    private void ready(String scope) {
        properties.getSupabaseValidation().setEnabled(true);
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(
                1, BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.ACCOUNTS, "approved-connection"),
                new BuildDecisions.Available(true, "approved-connection"), null));
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(), 1, "{}", "Approved", scope));
        when(provider.getIfAvailable()).thenReturn(factory);
        when(factory.open(eq(project), eq(context.runId()), anyList())).thenReturn(session);
        when(api.check(eq(session), anyList(), eq(true))).thenReturn(List.of(
                new ValidationCheck("SUPABASE_AUTH", ValidationCheck.Outcome.PASS, true, "Fixture accounts passed")));
        migration("002-tasks.sql", "CREATE TABLE tasks(id uuid);");
    }

    private void migration(String name, String sql) {
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        when(files.list(project, "candidate/supabase/migrations", 2)).thenReturn(List.of(new ProjectFiles.Entry(name, false, bytes.length)));
        when(files.readBytes(eq(project), eq("candidate/supabase/migrations"), eq(name), anyInt())).thenReturn(bytes);
    }

    @Test
    void unprunedApprovedEntitiesAndStagedSqlReachTheSameBrowserSession() {
        ready(SCOPE);
        var result = validation.evaluate(context, "candidate", scratch -> {
            assertSame(session, scratch);
            verify(session, never()).close();
            return "browser evidence";
        });
        assertEquals("browser evidence", result.value());
        assertTrue(result.applicable());
        assertTrue(result.checks().stream().noneMatch(ValidationCheck::blocksSuccess));
        verify(factory).open(project, context.runId(), List.of(new SupabaseScratch.Migration("002-tasks.sql", "CREATE TABLE tasks(id uuid);")));
        verify(api).check(eq(session), argThat(entities -> entities.size() == 2
                && entities.get(0).id().equals("tasks") && entities.get(1).id().equals("notes")), eq(true));
        verify(session).close();
        verify(files, never()).list(eq(project), eq("supabase/migrations"), anyInt());
    }

    @Test
    void disabledAcceptanceCannotBuildAgainstTheSavedConnector() {
        ready(SCOPE);
        properties.getSupabaseValidation().setEnabled(false);
        var called = new AtomicBoolean();
        var result = validation.evaluate(context, "candidate", scratch -> called.getAndSet(true));
        assertFalse(called.get());
        assertNull(result.value());
        assertTrue(result.checks().getFirst().unavailable());
        verifyNoInteractions(factory, api, files);
    }

    @Test
    void changedAnswersAndDuplicateOrUnsupportedEntitiesDoNotSilentlyLoseCoverage() {
        ready(SCOPE);
        var mapper = new ObjectMapper();
        assertThrows(IllegalArgumentException.class, () -> SupabaseValidationScope.entities(
                mapper.readTree(SCOPE.replace("\"requestedScope\":", "\"answersChanged\":true,\"requestedScope\":")), true));
        assertThrows(IllegalArgumentException.class, () -> SupabaseValidationScope.entities(
                mapper.readTree(SCOPE.replace("\"notes\"", "\"tasks\"")), true));
        assertThrows(IllegalArgumentException.class, () -> SupabaseValidationScope.entities(
                mapper.readTree(SCOPE.replace("\"STRING\"", "\"OBJECT\"")), true));
        assertThrows(IllegalArgumentException.class, () -> SupabaseValidationScope.entities(mapper.readTree(SCOPE), false));
    }

    @Test
    void missingMigrationsAreAnActionableSchemaFailureBeforeAnyBrowserWork() {
        ready(SCOPE);
        when(files.list(project, "candidate/supabase/migrations", 2)).thenReturn(List.of());
        var result = validation.evaluate(context, "candidate", scratch -> fail("Browser must not run without a schema"));
        assertEquals(ValidationCheck.Outcome.FAIL, result.checks().getFirst().outcome());
        assertFalse(result.checks().getFirst().unavailable());
        verifyNoInteractions(factory, api);
    }

    @Test
    void nestedOrMalformedMigrationHistoryIsReportedUnavailableRatherThanPartiallyApplied() {
        ready(SCOPE);
        when(files.list(project, "candidate/supabase/migrations", 2))
                .thenReturn(List.of(new ProjectFiles.Entry("nested", true, 0)));
        assertTrue(validation.evaluate(context, "candidate", scratch -> fail()).checks().getFirst().unavailable());
        when(files.list(project, "candidate/supabase/migrations", 2))
                .thenReturn(List.of(new ProjectFiles.Entry("001.sql", false, 2)));
        when(files.readBytes(eq(project), eq("candidate/supabase/migrations"), eq("001.sql"), anyInt()))
                .thenReturn(new byte[]{(byte) 0xc3, 0x28});
        assertTrue(validation.evaluate(context, "candidate", scratch -> fail()).checks().getFirst().unavailable());
        verifyNoInteractions(factory, api);
    }

    @Test
    void schemaFailureAndInfrastructureUnavailabilityRemainDistinct() {
        ready(SCOPE);
        doThrow(new SupabaseScratchException("Isolated schema rejected", true)).when(factory).open(any(), any(), anyList());
        assertEquals(ValidationCheck.Outcome.FAIL, validation.evaluate(context, "candidate", scratch -> fail()).checks().getFirst().outcome());
        doThrow(new SupabaseScratchException("Scratch transport unavailable", false)).when(factory).open(any(), any(), anyList());
        assertTrue(validation.evaluate(context, "candidate", scratch -> fail()).checks().getFirst().unavailable());
        verifyNoInteractions(api);
    }

    @Test
    void failedIndependentDataEvidenceIsKeptEvenWhenTheBrowserPasses() {
        ready(SCOPE);
        when(api.check(eq(session), anyList(), eq(true))).thenReturn(List.of(
                new ValidationCheck("SUPABASE_USER_ISOLATION", ValidationCheck.Outcome.FAIL, true, "Private row was visible")));
        var result = validation.evaluate(context, "candidate", scratch -> "page loaded");
        assertEquals("page loaded", result.value());
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("SUPABASE_USER_ISOLATION") && check.blocksSuccess()));
        verify(session).close();
    }

    @Test
    void cleanupFailureAndApprovalChangesBlockOtherwisePassingEvidence() {
        ready(SCOPE);
        doThrow(new IllegalStateException("Fixture cleanup failure")).when(session).close();
        var result = validation.evaluate(context, "candidate", scratch -> {
            when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(), 2, "{}", "Changed", SCOPE));
            return "loaded";
        });
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("SUPABASE_APPROVAL") && check.unavailable()));
        assertTrue(result.checks().stream().anyMatch(check -> check.id().equals("SUPABASE_CLEANUP") && check.unavailable()));
    }

    @Test
    void cancellationClosesTheScratchWithoutConvertingStopIntoPassingOrSkippedEvidence() {
        ready(SCOPE);
        var stopped = new CancellationException("Fixture stopped");
        assertSame(stopped, assertThrows(CancellationException.class,
                () -> validation.evaluate(context, "candidate", scratch -> { throw stopped; })));
        verify(session).close();
    }

    @Test
    void nonCloudProjectsDoNotOpenAnyScratchResources() {
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(
                new BuildDecisions.Choice(1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null),
                new BuildDecisions.Available(true, null), null));
        var result = validation.evaluate(context, "candidate", scratch -> { assertNull(scratch); return "local evidence"; });
        assertFalse(result.applicable());
        assertTrue(result.checks().isEmpty());
        verifyNoInteractions(factory, api, files, memory);
    }

    @Test
    void aLostApprovalNeverFallsBackToTheLiveConnector() {
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(null, new BuildDecisions.Available(true, null), "Approval changed"));
        var result = validation.evaluate(context, "candidate", scratch -> fail("Blocked approval must never call the browser"));
        assertTrue(result.checks().getFirst().unavailable());
        verifyNoInteractions(factory, api, files, memory);
    }
}
