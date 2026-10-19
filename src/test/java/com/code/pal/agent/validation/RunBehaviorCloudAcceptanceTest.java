package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunBehaviorCloudAcceptanceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID run = UUID.randomUUID();
    private final UUID projection = UUID.randomUUID();
    private final JdbcTemplate database = mock(JdbcTemplate.class);
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final AgentProperties properties = new AgentProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final RunBehaviorCriteria store = new RunBehaviorCriteria(database, mapper, memory);

    private BehaviorCriteria criteria(BehaviorCriteria.Provenance provenance) {
        return new BehaviorCriteria(1, List.of(new BehaviorCriteria.BrowserAction("heading",
                BehaviorCriteria.BrowserKind.ASSERT_TEXT, "h1", "Tasks")), List.of(), false, false, provenance);
    }

    private void ready(BuildDecisions.Storage storage, BehaviorCriteria recipe) {
        store.useDecisions(decisions);
        store.useProperties(properties);
        properties.getSupabaseValidation().setEnabled(true);
        properties.getSandbox().setRequired(true);
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(
                new BuildDecisions.Choice(1, storage, BuildDecisions.Auth.ACCOUNTS, "connection-revision"),
                new BuildDecisions.Available(true, "connection-revision"), null));
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(projection, 1, "{}", "Approved"));
        when(database.queryForObject(contains("count(*)"), eq(Integer.class), eq(run), eq(project))).thenReturn(1);
        when(database.queryForObject(startsWith("select criteria_json"), eq(String.class), eq(run), eq(project)))
                .thenReturn(mapper.writeValueAsString(recipe));
    }

    @Test
    void cloudAccountsCanStoreAdvisoryBrowserEvidenceWhileIndependentApiAcceptanceRemainsRequired() {
        var recipe = criteria(BehaviorCriteria.Provenance.MODEL_SPEC);
        ready(BuildDecisions.Storage.SUPABASE, recipe);
        assertDoesNotThrow(() -> store.install(run, project, projection, 1, recipe));
        assertFalse(recipe.authRequired(), "This receipt describes browser assertions, not independent account coverage");
        verify(database).update(startsWith("insert into agent_behavior_criteria"), eq(run), eq(project), eq(projection), eq(1L), anyString());
    }

    @Test
    void legacyAndPlatformAccountCriteriaCannotDropAuthenticationForAnyBackend() {
        for (var storage : List.of(BuildDecisions.Storage.SUPABASE, BuildDecisions.Storage.MERN)) {
            for (var provenance : Arrays.asList(null, BehaviorCriteria.Provenance.PLATFORM)) {
                var recipe = criteria(provenance);
                ready(storage, recipe);
                assertThrows(IllegalArgumentException.class, () -> store.install(run, project, projection, 1, recipe));
            }
        }
        verify(database, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void modelBrowserProvenanceCannotBypassMernAccountChecksOrADisabledCloudAdapter() {
        var recipe = criteria(BehaviorCriteria.Provenance.MODEL_SPEC);
        ready(BuildDecisions.Storage.MERN, recipe);
        assertThrows(IllegalArgumentException.class, () -> store.install(run, project, projection, 1, recipe));
        ready(BuildDecisions.Storage.SUPABASE, recipe);
        properties.getSupabaseValidation().setEnabled(false);
        assertThrows(IllegalArgumentException.class, () -> store.install(run, project, projection, 1, recipe));
        verify(database, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void cloudAdvisoryAdmissionRequiresBothRuntimeChecksAndTheDockerBoundary() {
        var recipe = criteria(BehaviorCriteria.Provenance.MODEL_SPEC);
        ready(BuildDecisions.Storage.SUPABASE, recipe);
        properties.getSandbox().setRequired(false);
        assertThrows(IllegalArgumentException.class, () -> store.install(run, project, projection, 1, recipe));
        properties.getSandbox().setRequired(true);
        properties.getSandbox().setRuntimeCheck(false);
        assertThrows(IllegalArgumentException.class, () -> store.install(run, project, projection, 1, recipe));
        verify(database, never()).update(anyString(), any(Object[].class));
    }
}
