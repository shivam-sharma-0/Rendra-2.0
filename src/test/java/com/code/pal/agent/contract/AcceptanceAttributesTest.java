package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcceptanceAttributesTest {
    private static final List<BehaviorCriteria.BrowserAction> FILL = List.of(
            new BehaviorCriteria.BrowserAction("ui_fill", BrowserKind.FILL, "input[name='title']", "Fixture task"));

    @Test void theOnlyControlWithTheSelectorsIdGainsTheName() {
        // Live E2E 2026-10-27: Home rendered <input id="title" ...> and ui_fill selected input[name='title'].
        String home = "<input\n  id=\"title\"\n  type=\"text\"\n  onChange={(e) => setTitle(e.target.value)}\n/>\n<input id=\"dueDate\" />";
        var fixed = AcceptanceAttributes.nameFields(FILL, Map.of("client/src/pages/Home.tsx", home, "client/src/pages/Other.tsx", "<p/>"));
        assertEquals(home.replaceFirst("<input", "<input name=\"title\""), fixed.get("client/src/pages/Home.tsx"));
        assertEquals("<p/>", fixed.get("client/src/pages/Other.tsx"));
    }

    @Test void namedAmbiguousOrDifferentlyNamedControlsAreLeftForRepair() {
        for (var sources : List.of(
                Map.of("a.tsx", "<input id=\"title\" />", "b.tsx", "<input name=\"title\" />"),
                Map.of("a.tsx", "<input id=\"title\" />", "b.tsx", "<input id='title' />"),
                Map.of("a.tsx", "<input id=\"title\" onChange={(e) => go(e)} name=\"heading\" />"),
                Map.of("a.tsx", "<textarea id=\"title\" />")))
            assertEquals(sources, AcceptanceAttributes.nameFields(FILL, sources));
        var click = List.of(new BehaviorCriteria.BrowserAction("ui_add", BrowserKind.CLICK, "input[name='title']", null));
        assertEquals(Map.of("a.tsx", "<input id=\"title\" />"), AcceptanceAttributes.nameFields(click, Map.of("a.tsx", "<input id=\"title\" />")));
    }

    private static final List<BehaviorCriteria.BrowserAction> SAVE = List.of(
            new BehaviorCriteria.BrowserAction("ui_edit_fill", BrowserKind.FILL, "input[name='editTitle']", "Edited task"),
            new BehaviorCriteria.BrowserAction("ui_edit", BrowserKind.CLICK, "button[data-action='save']", null));

    @Test void theOnlySubmitButtonBesideThePreparedFieldGainsTheClickedAction() {
        // Live E2E 2026-10-28 (run 32): EditTodo rendered <button type="submit">Save</button>; ui_edit selected data-action='save'.
        String edit = "<input name=\"editTitle\" />\n<button\n  type=\"submit\"\n  disabled={saving}\n>Save</button>";
        var fixed = AcceptanceAttributes.actionButtons(SAVE, Map.of("client/src/pages/EditTodo.tsx", edit, "client/src/pages/Other.tsx",
                "<button type=\"submit\">Go</button>"));
        assertEquals(edit.replaceFirst("<button", "<button data-action=\"save\""), fixed.get("client/src/pages/EditTodo.tsx"));
        assertEquals("<button type=\"submit\">Go</button>", fixed.get("client/src/pages/Other.tsx"));
    }

    @Test void declaredAmbiguousOrUnpreparedButtonsAreLeftForRepair() {
        for (var sources : List.of(
                Map.of("a.tsx", "<input name=\"editTitle\" /><button type=\"submit\">Save</button>", "b.tsx", "<button data-action=\"save\">Save</button>"),
                Map.of("a.tsx", "<input name=\"editTitle\" /><button type=\"submit\">Save</button><button type='submit'>Also</button>"),
                Map.of("a.tsx", "<input name=\"editTitle\" /><button type=\"submit\" data-action=\"update\">Save</button>"),
                Map.of("a.tsx", "<input name=\"editTitle\" /><button onClick={() => save()}>Save</button>"),
                Map.of("a.tsx", "<input name=\"other\" /><button type=\"submit\">Save</button>")))
            assertEquals(sources, AcceptanceAttributes.actionButtons(SAVE, sources));
        var unprepared = List.of(SAVE.get(1));
        var sources = Map.of("a.tsx", "<input name=\"editTitle\" /><button type=\"submit\">Save</button>");
        assertEquals(sources, AcceptanceAttributes.actionButtons(unprepared, sources));
    }
}
