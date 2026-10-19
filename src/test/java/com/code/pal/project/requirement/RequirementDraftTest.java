package com.code.pal.project.requirement;

import com.code.pal.project.spec.SpecNormalizer;
import com.code.pal.project.spec.ApprovedSpec.Storage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequirementDraftTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String SOURCE = """
            {"goal":"Track tasks","pages":[{"id":"home","path":"/","title":"Tasks","featureIds":["list","loading"]}],
             "features":[{"id":"list","description":"List tasks"},{"id":"loading","description":"Show progress"}],
             "acceptance":[{"id":"items","featureId":"list","steps":[{"kind":"ASSERT_TEXT","selector":"h1","value":"Tasks"}]}]}
            """;

    @Test
    void uncoveredScopeSurvivesWithoutInventingExecutableChecks() {
        String stored = RequirementDraft.capture(json.readTree(SOURCE));
        var review = RequirementDraft.review(stored, false);
        assertEquals(List.of("list", "loading"), review.features().stream().map(value -> value.id()).toList());
        assertTrue(review.features().getFirst().checkPlanned());
        assertFalse(review.features().getLast().checkPlanned());
        assertEquals(1, SpecNormalizer.normalize(json.readTree(stored),
                Storage.BROWSER).features().size());
    }

    @Test
    void changedAnswersRetainScopeButInvalidateEveryStaleCheck() {
        String stored = RequirementDraft.capture(json.readTree(SOURCE));
        String changed = RequirementDraft.withChangedAnswers(stored, List.of("Use a shared workspace"));
        var review = RequirementDraft.review(changed, false);
        assertTrue(review.answersChanged());
        assertEquals(2, review.features().size());
        assertTrue(review.features().stream().noneMatch(value -> value.checkPlanned()));
        assertTrue(json.readTree(changed).path("acceptance").isEmpty());
        assertTrue(changed.contains("Use a shared workspace"));
    }

    @Test
    void noExecutableChecksStillPreservesTheRequestedFeatures() {
        String source = SOURCE.replace("ASSERT_TEXT", "HOVER");
        var review = RequirementDraft.review(RequirementDraft.capture(json.readTree(source)), false);
        assertNotNull(review);
        assertEquals(2, review.features().size());
        assertTrue(review.features().stream().noneMatch(value -> value.checkPlanned()));
        assertNull(RequirementDraft.review(null, false));
        assertNull(RequirementDraft.review("not json", false));
    }
}
