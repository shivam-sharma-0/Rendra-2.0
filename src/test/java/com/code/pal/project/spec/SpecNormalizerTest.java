package com.code.pal.project.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String VALID = """
            {"title":"Tasks","goal":"","pages":[{"id":"home page","path":"home","title":"Home","featureIds":["add-task","ghost"]},
              {"id":"stats","path":"/stats","featureIds":[]}],
             "entities":[{"id":"task","fields":[{"name":"title","kind":"string","required":true}],"ownership":""},
              {"id":"empty","fields":[]}],
             "features":[{"id":"add-task","kind":"local_crud","description":"Add tasks","pageIds":[],"entityIds":["task","empty"]},
              {"id":"chart","kind":"CHART","description":"Show stats","pageIds":["stats"]}],
             "theme":{"background":"white","foreground":"#111","accent":"#4f46e5","muted":"#888","radius":"99px","font":"comic"},
             "acceptance":[{"id":"adds","featureId":"add-task","steps":[{"kind":"fill","selector":"input","value":"Milk"},
              {"kind":"ASSERT_COUNT","selector":"li","value":"1"}],"expected":"A task appears"},
              {"id":"chart-shows","featureId":"chart","steps":[{"kind":"ASSERT_TEXT","selector":"h1","value":"Stats"}]}]}
            """;

    @Test
    void cosmeticProblemsAreRepairedWithoutTouchingBehaviour() {
        var spec = SpecNormalizer.normalize(mapper.readTree(VALID), ApprovedSpec.Storage.BROWSER);

        assertEquals(List.of("home_page", "stats"), spec.pages().stream().map(ApprovedSpec.Page::id).toList());
        assertEquals("/", spec.pages().getFirst().path(), "an invalid first route becomes the root");
        assertEquals(List.of("add_task"), spec.pages().getFirst().featureIds(), "unknown feature links are dropped");
        assertEquals(List.of("home_page"), spec.features().getFirst().pageIds(), "links agree in both directions");
        assertEquals(List.of("task"), spec.features().getFirst().entityIds(), "an entity without fields is dropped");
        assertEquals("LOCAL_CRUD", spec.features().getFirst().kind());
        assertEquals(ApprovedSpec.Storage.BROWSER, spec.entities().getFirst().persistence());
        assertEquals("#ffffff", spec.theme().background(), "unsafe colors fall back");
        assertEquals("8px", spec.theme().radius());
        assertEquals("system", spec.theme().font());
        assertEquals(List.of("FILL", "ASSERT_COUNT"), spec.acceptance().getFirst().steps().stream().map(ApprovedSpec.Step::kind).toList(),
                "every approved step is kept");
        assertEquals("Tasks", spec.goal(), "a blank goal falls back to the title");
    }

    @Test
    void anInvalidStepRejectsTheSpecInsteadOfWeakeningIt() {
        String draft = VALID.replace("{\"kind\":\"ASSERT_COUNT\",\"selector\":\"li\",\"value\":\"1\"}",
                "{\"kind\":\"ASSERT_COUNT\",\"selector\":\"li\",\"value\":\"one\"}");
        var error = assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(draft), ApprovedSpec.Storage.BROWSER));
        assertTrue(error.getMessage().contains("invalid step"), error.getMessage());
    }

    @Test
    void aScenarioWithoutAnAssertionOrAFeatureWithoutACheckRejectsTheSpec() {
        String noAssertion = VALID.replace(",\n  {\"kind\":\"ASSERT_COUNT\",\"selector\":\"li\",\"value\":\"1\"}", "")
                .replace(",{\"kind\":\"ASSERT_COUNT\",\"selector\":\"li\",\"value\":\"1\"}", "");
        assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(noAssertion), ApprovedSpec.Storage.BROWSER));

        String uncovered = VALID.replace(",\n  {\"id\":\"chart-shows\",\"featureId\":\"chart\",\"steps\":[{\"kind\":\"ASSERT_TEXT\",\"selector\":\"h1\",\"value\":\"Stats\"}]}", "");
        var error = assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(uncovered), ApprovedSpec.Storage.BROWSER));
        assertTrue(error.getMessage().contains("no acceptance check"), error.getMessage());

        String unknownFeature = VALID.replace("\"featureId\":\"chart\"", "\"featureId\":\"ghost\"");
        assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(unknownFeature), ApprovedSpec.Storage.BROWSER));
    }

    @Test
    void overflowDuplicateIdsAndUnsupportedKindsRejectInsteadOfDroppingChecks() {
        var draft = (tools.jackson.databind.node.ObjectNode) mapper.readTree(VALID);
        var scenarios = (tools.jackson.databind.node.ArrayNode) draft.path("acceptance");
        for (int index = 0; index < 23; index++) {
            scenarios.add(scenarios.get(0).deepCopy());
            ((tools.jackson.databind.node.ObjectNode) scenarios.get(scenarios.size() - 1)).put("id", "extra" + index);
        }
        assertThrows(IllegalArgumentException.class, () -> SpecNormalizer.normalize(draft, ApprovedSpec.Storage.BROWSER),
                "a 25th scenario is never silently dropped");

        String duplicate = VALID.replace("\"id\":\"chart-shows\"", "\"id\":\"adds\"");
        assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(duplicate), ApprovedSpec.Storage.BROWSER));

        String apiKind = VALID.replace("\"id\":\"chart-shows\",", "\"id\":\"chart-shows\",\"kind\":\"API\",");
        assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(apiKind), ApprovedSpec.Storage.BROWSER));
    }

    @Test
    void twoPagesOnTheSameRouteAreRejectedNotRenamed() {
        String draft = VALID.replace("\"path\":\"home\"", "\"path\":\"/\"").replace("\"path\":\"/stats\"", "\"path\":\"/\"");
        assertThrows(IllegalArgumentException.class,
                () -> SpecNormalizer.normalize(mapper.readTree(draft), ApprovedSpec.Storage.BROWSER));
    }

    @Test
    void aDraftIsShapedToItsCheckableBehaviourBeforeApproval() {
        String uncovered = VALID.replace("\"featureIds\":[]}", "\"featureIds\":[\"chart\",\"loading_state\"]}")
                .replace("\"pageIds\":[\"stats\"]}", "\"pageIds\":[\"stats\"]},{\"id\":\"loading_state\",\"description\":\"Show a spinner\"}")
                .replace("{\"kind\":\"ASSERT_TEXT\",\"selector\":\"h1\",\"value\":\"Stats\"}",
                        "{\"kind\":\"HOVER\",\"selector\":\"h1\"},{\"kind\":\"ASSERT_TEXT\",\"selector\":\"h1\",\"value\":\"Stats\"}");

        var shaped = SpecNormalizer.draft(mapper.readTree(uncovered));
        var spec = SpecNormalizer.normalize(shaped, ApprovedSpec.Storage.BROWSER);

        assertEquals(List.of("add_task"), spec.features().stream().map(ApprovedSpec.Feature::id).toList(),
                "a feature without a runnable check leaves the draft instead of rejecting every other check");
        assertEquals(List.of("adds"), spec.acceptance().stream().map(ApprovedSpec.Acceptance::id).toList());
        assertEquals(2, spec.acceptance().getFirst().steps().size(), "kept scenarios are untouched");
        assertEquals(List.of(), spec.pages().get(1).featureIds(), "dropped features leave the page links too");
    }

    @Test
    void aDraftWithNothingCheckableIsNoSpec() {
        String noChecks = VALID.replaceAll("\"kind\":\"ASSERT_[A-Z]+\"", "\"kind\":\"CLICK\"");
        assertEquals(null, SpecNormalizer.draft(mapper.readTree(noChecks)));
        assertEquals(null, SpecNormalizer.draft(mapper.readTree("{}")));
        assertEquals(null, SpecNormalizer.draft(null));
    }

    @Test
    void anEmptyDraftIsRejectedRatherThanInvented() {
        assertThrows(IllegalArgumentException.class, () -> SpecNormalizer.normalize(mapper.readTree("{}"), ApprovedSpec.Storage.NONE));
    }
}
