package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpecAcceptanceHooksTest {
    ApprovedSpec spec(List<ApprovedSpec.Step> steps) {
        var original = SpecFixtures.approved();
        return SpecFixtures.with(original, original.spec().pages(), original.spec().entities(), original.spec().features(),
                List.of(new ApprovedSpec.Acceptance("contact_link_navigation", "content", "BROWSER", steps, "Contact link is usable")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:hello@example.invalid", "#contact", "https://example.invalid/contact"})
    void nonRouteNavigationIsAnHrefAssertionWithStableHooksAndNoBrowserEscape(String href) {
        var approved = spec(List.of(new ApprovedSpec.Step("CLICK", "a[href='" + href + "']", null),
                new ApprovedSpec.Step("ASSERT_ROUTE", null, "/contact")));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertEquals(List.of("contact_link_navigation_0", "contact_link_navigation_1"), actions.stream().map(action -> action.id()).toList());
        assertTrue(actions.stream().allMatch(action -> action.kind() == BrowserKind.ASSERT_COUNT && "1".equals(action.value())));
        assertTrue(actions.stream().allMatch(action -> action.selector().contains("data-testid") && action.selector().contains(href)));
        assertEquals(SpecPlanValues.acceptance(approved), SpecPlanValues.acceptance(approved));
    }

    @Test
    void repeatedTargetsShareHooksAndCheckedStateAssertionsRetainTheirMeaning() {
        var approved = spec(List.of(new ApprovedSpec.Step("CLICK", "#complete", null),
                new ApprovedSpec.Step("ASSERT_COUNT", "#complete:checked", "1"),
                new ApprovedSpec.Step("ASSERT_COUNT", "#complete:checked", "0")));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertEquals(BrowserKind.CLICK, actions.getFirst().kind());
        assertTrue(actions.getFirst().selector().contains("data-testid"));
        assertEquals(actions.getFirst().selector() + ":checked", actions.get(1).selector());
        assertEquals(actions.get(1).selector(), actions.get(2).selector());
        assertEquals("1", actions.get(1).value());
        assertEquals("0", actions.get(2).value());
    }

    @Test
    void ordinaryActionsAndRouteAssertionsAreNeverDroppedOrRelabelledAsLinks() {
        var approved = spec(List.of(new ApprovedSpec.Step("FILL", "#email", "visitor@example.invalid"),
                new ApprovedSpec.Step("CLICK", "#send", null), new ApprovedSpec.Step("ASSERT_TEXT", "#status", "Message received"),
                new ApprovedSpec.Step("RELOAD", null, null), new ApprovedSpec.Step("ASSERT_ROUTE", null, "/")));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertEquals(5, actions.size());
        assertEquals(List.of(BrowserKind.FILL, BrowserKind.CLICK, BrowserKind.ASSERT_TEXT, BrowserKind.RELOAD, BrowserKind.ASSERT_ROUTE),
                actions.stream().map(action -> action.kind()).toList());
        assertTrue(actions.get(2).selector().contains("data-testid"));
        assertEquals("Message received", actions.get(2).value());
        assertEquals("/", actions.get(4).value());
    }

    @Test
    void anApprovedInternalRouteStillNavigatesBeforeDestinationFormInteractions() {
        var original = SpecFixtures.approved();
        var pages = List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content")),
                new ApprovedSpec.Page("contact", "/contact", "Contact", List.of("content")));
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Contact form", List.of("home", "contact"), List.of());
        var scenario = new ApprovedSpec.Acceptance("visitContact", "content", "BROWSER", List.of(
                new ApprovedSpec.Step("CLICK", "a[href='/contact']", null), new ApprovedSpec.Step("ASSERT_ROUTE", null, "/contact"),
                new ApprovedSpec.Step("FILL", "#name", "Synthetic visitor")), "Contact form can be filled");
        var approved = SpecFixtures.with(original, pages, List.of(), List.of(feature), List.of(scenario));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertEquals(BrowserKind.CLICK, actions.getFirst().kind());
        assertTrue(actions.getFirst().selector().contains("data-testid"));
        assertTrue(actions.getFirst().selector().contains("[href=\"/contact\"]"));
        assertEquals(BrowserKind.ASSERT_ROUTE, actions.get(1).kind());
        assertEquals("/contact", actions.get(1).value());
        assertEquals(BrowserKind.FILL, actions.get(2).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,test", "//example.invalid", "/unapproved-page"})
    void anUnsafeOrUnapprovedInternalDestinationCannotBeCertifiedAsALink(String href) {
        assertThrows(IllegalArgumentException.class, () -> SpecPlanValues.acceptance(spec(
                List.of(new ApprovedSpec.Step("CLICK", "a[href='" + href + "']", null)))));
    }

    @Test
    void ambiguousGenericButtonsGetDistinctActionHooksInsteadOfBindingToTheFirstButton() {
        var approved = spec(List.of(new ApprovedSpec.Step("CLICK", "button", null),
                new ApprovedSpec.Step("ASSERT_TEXT", "output", "Added"), new ApprovedSpec.Step("CLICK", "button", null),
                new ApprovedSpec.Step("ASSERT_TEXT", "output", "Deleted")));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertNotEquals(actions.getFirst().selector(), actions.get(2).selector());
        assertEquals("Added", actions.get(1).value());
        assertEquals("Deleted", actions.get(3).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"mailto:hello@example.invalid", "#contact"})
    void anExplicitClickDestinationValueIsPreservedWithoutInventingARoute(String href) {
        var actions = SpecPlanValues.acceptance(spec(List.of(new ApprovedSpec.Step("CLICK", "a", href)))).browser();
        assertEquals(BrowserKind.ASSERT_COUNT, actions.getFirst().kind());
        assertTrue(actions.getFirst().selector().contains("[href=\"" + href + "\"]"));
    }

    @Test
    void anExplicitMailtoCannotBeRewrittenToAnApprovedRouteFromTheFollowingAssertion() {
        var original = SpecFixtures.approved();
        var pages = List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content")),
                new ApprovedSpec.Page("contact", "/contact", "Contact", List.of("content")));
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Contact link", List.of("home", "contact"), List.of());
        var scenario = new ApprovedSpec.Acceptance("contact", "content", "BROWSER", List.of(
                new ApprovedSpec.Step("CLICK", "a[href='mailto:hello@example.invalid']", null),
                new ApprovedSpec.Step("ASSERT_ROUTE", null, "/contact")), "Contact link has the expected destination");
        var approved = SpecFixtures.with(original, pages, List.of(), List.of(feature), List.of(scenario));
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertTrue(actions.stream().allMatch(action -> action.kind() == BrowserKind.ASSERT_COUNT));
        assertTrue(actions.stream().allMatch(action -> action.selector().contains("mailto:hello@example.invalid")));
    }

    @Test
    void linkOnlyInternalNavigationChecksItsHrefWithoutFollowingIt() {
        var actions = SpecPlanValues.acceptance(spec(List.of(new ApprovedSpec.Step("CLICK", "a[href='/']", null),
                new ApprovedSpec.Step("ASSERT_ROUTE", null, "/")))).browser();
        assertEquals(2, actions.size());
        assertTrue(actions.stream().allMatch(action -> action.kind() == BrowserKind.ASSERT_COUNT));
        assertTrue(actions.stream().allMatch(action -> action.selector().contains("[href=\"/\"]")));
    }

    @Test
    void genericLinkPredicatesStayBoundedWhenTheSpecHasManyLongPageRoutes() {
        var original = SpecFixtures.approved();
        var pages = new ArrayList<ApprovedSpec.Page>();
        pages.add(new ApprovedSpec.Page("home", "/", "Home", List.of("content")));
        for (int index = 1; index < 20; index++) {
            pages.add(new ApprovedSpec.Page("page" + index, "/" + "longroute".repeat(10) + index, "Page", List.of("content")));
        }
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Content with contact link",
                pages.stream().map(ApprovedSpec.Page::id).toList(), List.of());
        var scenario = new ApprovedSpec.Acceptance("contact", "content", "BROWSER",
                List.of(new ApprovedSpec.Step("CLICK", "a.contact", null)), "Contact link exists");
        var compiled = SpecPlanValues.acceptance(SpecFixtures.with(original, pages, List.of(), List.of(feature), List.of(scenario)));
        assertEquals(BrowserKind.ASSERT_COUNT, compiled.browser().getFirst().kind());
        assertTrue(compiled.browser().getFirst().selector().length() <= 256);
    }
}
