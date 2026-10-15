package com.code.pal.project.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecNormalizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aMessyModelDraftBecomesAValidSpecWithoutAnotherCall() {
        var draft = mapper.readTree("""
                {"title":"Tasks","goal":"","pages":[{"id":"home page","path":"home","title":"Home","featureIds":["add-task","ghost"]},
                  {"id":"stats","path":"/stats","featureIds":[]}],
                 "entities":[{"id":"task","fields":[{"name":"title","kind":"string","required":true}],"ownership":""},
                  {"id":"empty","fields":[]}],
                 "features":[{"id":"add-task","kind":"local_crud","description":"Add tasks","pageIds":[],"entityIds":["task","empty"]},
                  {"id":"chart","kind":"CHART","description":"Show stats","pageIds":["stats"]}],
                 "theme":{"background":"white","foreground":"#111","accent":"#4f46e5","muted":"#888","radius":"99px","font":"comic"},
                 "acceptance":[{"id":"adds","featureId":"add-task","steps":[{"kind":"fill","selector":"input","value":"Milk"},
                  {"kind":"ASSERT_COUNT","selector":"li","value":"one"},{"kind":"HOVER","selector":"li"}],"expected":"A task appears"}]}
                """);

        var spec = SpecNormalizer.normalize(draft, ApprovedSpec.Storage.BROWSER);

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
        assertEquals(List.of("FILL"), spec.acceptance().getFirst().steps().stream().map(ApprovedSpec.Step::kind).toList(),
                "invalid steps are dropped");
        assertTrue(spec.acceptance().stream().anyMatch(scenario -> scenario.featureId().equals("chart")
                && scenario.steps().getFirst().kind().equals("ASSERT_ROUTE")), "every feature keeps a check");
        assertEquals("Tasks", spec.goal(), "a blank goal falls back to the title");
    }

    @Test
    void anEmptyDraftStillGivesAOnePageSpec() {
        var spec = SpecNormalizer.normalize(mapper.readTree("{}"), ApprovedSpec.Storage.NONE);
        assertEquals(1, spec.pages().size());
        assertEquals(1, spec.features().size());
        assertEquals(1, spec.acceptance().size());
    }
}
