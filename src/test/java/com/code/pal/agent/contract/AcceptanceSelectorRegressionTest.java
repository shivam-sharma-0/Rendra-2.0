package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AcceptanceSelectorRegressionTest {
    private List<BrowserAction> actions(ApprovedSpec.Step... steps) {
        return SpecPlanValues.acceptance(new SpecAcceptanceHooksTest().spec(List.of(steps))).browser();
    }

    @ParameterizedTest
    @ValueSource(strings = {".todo-item", "li"})
    void deletionChecksReuseThePositivelyAssertedCollection(String selector) {
        var actions = actions(new ApprovedSpec.Step("ASSERT_COUNT", selector, "1"),
                new ApprovedSpec.Step("CLICK", "#delete", null), new ApprovedSpec.Step("ASSERT_COUNT", selector, "0"));
        assertEquals(actions.getFirst().selector(), actions.getLast().selector());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00"})
    void absenceWithoutPositiveEvidenceKeepsTheOriginalSelector(String value) {
        var action = actions(new ApprovedSpec.Step("ASSERT_COUNT", ".todo-item", value)).getFirst();
        assertEquals(".todo-item", action.selector(), "A never-rendered test hook must not prove deletion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"button[aria-label=\"Add a task\"]", "a button", "a > span", "button[data-label=\"a[href='/']\"]"})
    void onlyTheLastCompoundAnchorTypeIsALink(String selector) {
        assertEquals(BrowserKind.CLICK, actions(new ApprovedSpec.Step("CLICK", selector, null)).getFirst().kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a[href^='/blog']", "a[href*='/blog']", "a[href^='blog/']"})
    void internalPartialHrefAssertionsDoNotRequireAnExternalScheme(String selector) {
        var action = actions(new ApprovedSpec.Step("CLICK", selector, null)).getFirst();
        assertEquals(BrowserKind.ASSERT_COUNT, action.kind());
        assertTrue(action.selector().contains("blog"));
        assertFalse(action.selector().contains(":is("), "A relative href cannot satisfy the external-link predicate");
    }

    @Test
    void partialInternalNavigationNeedsAnExactDestinationBeforeUiInteractions() {
        var error = assertThrows(IllegalArgumentException.class, () -> actions(
                new ApprovedSpec.Step("CLICK", "a[href^='/blog']", null), new ApprovedSpec.Step("FILL", "#search", "Post")));
        assertTrue(error.getMessage().contains("exact"));
    }
}
