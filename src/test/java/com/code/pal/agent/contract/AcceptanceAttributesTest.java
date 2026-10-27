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
}
