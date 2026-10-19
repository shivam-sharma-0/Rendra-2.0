package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrustedFeatureOutcomesTest {
    private final UUID project = UUID.randomUUID();
    private final ApprovedProjectMemory memory = mock(ApprovedProjectMemory.class);
    private final BuildDecisions decisions = mock(BuildDecisions.class);
    private final AgentProperties properties = new AgentProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final TrustedFeatureOutcomes outcomes = new TrustedFeatureOutcomes(memory, decisions, properties, mapper);
    private static final String DRAFT = """
            {"features":[],"requestedScope":{"features":[
              {"id":"contact","kind":"CONTACT_FORM","entityIds":[]},
              {"id":"tasks","kind":"LOCAL_CRUD","entityIds":["task"]},
              {"id":"custom","kind":"CUSTOM_WORKFLOW","entityIds":[]}],
             "entities":[{"id":"task","fields":[{"name":"title","kind":"STRING","required":true}]}]}}
            """;

    private void ready(String draft) {
        properties.getFeatureOutcomes().setEnabled(true);
        when(memory.current(project)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(), 1, "{}", "Approved", draft));
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(
                1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null), new BuildDecisions.Available(true, null), null));
    }

    @Test
    void enabledTemplatesKeepMissingLegacyScopeVisibleAsIncompleteCoverage() {
        properties.getFeatureOutcomes().setEnabled(true);
        var plan = outcomes.plan(project);
        assertEquals(List.of("CURRENT_APPROVED_SCOPE"), plan.uncovered());
        var checks = outcomes.checks(plan, null);
        assertEquals(1, checks.size());
        assertEquals("FEATURE_COVERAGE", checks.getFirst().id());
        assertFalse(checks.getFirst().required());
        assertEquals(ValidationCheck.Outcome.SKIPPED, checks.getFirst().outcome());
    }

    @Test
    void templatesUseCompleteApprovedScopeAndKeepUnsupportedFeaturesExplicit() {
        ready(DRAFT);
        var plan = outcomes.plan(project);
        assertEquals(List.of(new TrustedFeatureOutcomes.Definition("contact", "FORM_FEEDBACK", null),
                new TrustedFeatureOutcomes.Definition("tasks", "BROWSER_CRUD", "title")), plan.definitions());
        assertEquals(List.of("custom"), plan.uncovered());
        var checks = outcomes.checks(plan, mapper.readTree("""
                {"outcomes":[{"id":"contact","outcome":"PASS"},{"id":"tasks","outcome":"FAIL"}]}
                """));
        assertFalse(checks.getFirst().required());
        assertEquals("FEATURE_COVERAGE", checks.getFirst().id());
        assertTrue(checks.getLast().blocksSuccess());
        assertTrue(checks.get(1).reason().contains("delivery is not certified"));
    }

    @Test
    void missingDuplicateReorderedOrUnknownEvidenceNeverPassesARequiredFeature() {
        ready(DRAFT);
        var plan = outcomes.plan(project);
        for (String report : List.of("{}", "{\"outcomes\":[]}",
                "{\"outcomes\":[{\"id\":\"tasks\",\"outcome\":\"PASS\"},{\"id\":\"contact\",\"outcome\":\"PASS\"}]}",
                "{\"outcomes\":[{\"id\":\"contact\",\"outcome\":\"PASS\"},{\"id\":\"contact\",\"outcome\":\"PASS\"}]}",
                "{\"outcomes\":[{\"id\":\"contact\",\"outcome\":\"OK\"},{\"id\":\"tasks\",\"outcome\":\"PASS\"}]}")) {
            var checks = outcomes.checks(plan, mapper.readTree(report));
            assertTrue(checks.stream().filter(ValidationCheck::required).allMatch(ValidationCheck::unavailable));
        }
    }

    @Test
    void guidanceContainsOnlyTheNativeContractAndNeverThePrivateRandomFixture() {
        ready(DRAFT);
        String guidance = outcomes.guidance(outcomes.plan(project));
        assertTrue(guidance.contains("data-rendra-feature") && guidance.contains("delivery"));
        assertFalse(guidance.contains("check-") || guidance.contains("updated-"));
        var payload = outcomes.browserData(outcomes.plan(project), List.of());
        assertEquals(2, ((List<?>) payload.get("outcomes")).size());
        assertFalse(payload.containsKey("fixture"));
    }

    @Test
    void missingNativeContainersProduceActionableBoundedRepairEvidence() {
        ready(DRAFT);
        var plan = outcomes.plan(project);
        var report = mapper.readTree("""
                {"outcomes":[{"id":"contact","outcome":"FAIL","phase":"ready","reason":"FEATURE_ROOT_MISSING"},
                {"id":"tasks","outcome":"FAIL","phase":"ready","reason":"NATIVE_FORM_MISSING"}]}
                """);
        var checks = outcomes.checks(plan, report);
        var contact = checks.stream().filter(check -> check.id().equals("FEATURE_contact")).findFirst().orElseThrow();
        assertTrue(contact.reason().contains("data-rendra-feature=contact"));
        assertTrue(contact.reason().contains("data-rendra-feature-link=contact"));
        assertTrue(contact.required());
        assertEquals(ValidationCheck.Outcome.FAIL, contact.outcome());
    }

    @Test
    void missingCrudInputReportsTheExactApprovedFieldInsteadOfOnlyThePhase() {
        ready(DRAFT);
        var checks = outcomes.checks(outcomes.plan(project), mapper.readTree("""
                {"outcomes":[{"id":"contact","outcome":"PASS"},
                {"id":"tasks","outcome":"FAIL","phase":"prepare","reason":"FIELD_MISSING"}]}
                """));
        var tasks = checks.getLast();
        assertTrue(tasks.blocksSuccess());
        assertTrue(tasks.reason().contains("name=title"));
        assertTrue(tasks.reason().contains("phase=prepare"));
    }

    @Test
    void changedAnswersAndAmbiguousOrUnmappedCrudRemainUnverifiedInsteadOfShrinkingScope() {
        ready(DRAFT.replace("\"features\":[]", "\"answersChanged\":true,\"features\":[]"));
        assertTrue(outcomes.plan(project).definitions().isEmpty());
        assertFalse(outcomes.plan(project).uncovered().isEmpty());
        ready(DRAFT.replace("\"entityIds\":[\"task\"]", "\"entityIds\":[\"missing\"]"));
        assertTrue(outcomes.plan(project).uncovered().contains("tasks"));
    }

    @Test
    void browserStorageTemplatesCannotCertifyServerRecordsOrAccounts() {
        ready(DRAFT);
        when(decisions.state(project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(
                1, BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.ACCOUNTS, "connection"), new BuildDecisions.Available(true, "connection"), null));
        var plan = outcomes.plan(project);
        assertTrue(plan.definitions().isEmpty());
        assertTrue(plan.uncovered().containsAll(List.of("contact", "tasks", "custom")));
    }

    @Test
    void rolloutIsOffByDefaultAndCoverageIdentityIncludesPolicyChanges() {
        assertEquals(TrustedFeatureOutcomes.Plan.EMPTY, outcomes.plan(project));
        verifyNoInteractions(memory, decisions);
        ready(DRAFT);
        String original = outcomes.plan(project).binding();
        properties.getFeatureOutcomes().setStepTimeoutMs(3_000);
        assertNotEquals(original, outcomes.plan(project).binding());
    }
}
