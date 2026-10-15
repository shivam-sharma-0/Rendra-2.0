package com.code.pal.agent.contract;

import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockConstruction;

class DeterministicContentPlanTest {
    private final DeterministicPlan planner = new DeterministicPlan(new ObjectMapper());
    private final BatchBudget.Limits budget = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);

    ApprovedSpec twoPages(ApprovedSpec.RenderStack stack) {
        var original = SpecFixtures.approved();
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Bakery introduction and opening hours", List.of("home", "about"), List.of());
        var pages = List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content")),
                new ApprovedSpec.Page("about", "/about", "About", List.of("content")));
        var changed = SpecFixtures.with(original, pages, List.of(), List.of(feature), original.spec().acceptance());
        return SpecFixtures.envelope(changed.spec(), new ApprovedSpec.Desired(stack, ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE),
                ApprovedSpec.Readiness.READY);
    }

    @Test
    void smallMultiPageStaticContentUsesOneDeterministicBatchAndNoPlanner() {
        try (var calls = mockConstruction(ContractPlanner.class)) {
            var layout = planner.plan(twoPages(ApprovedSpec.RenderStack.STATIC), budget).orElseThrow();
            assertEquals(List.of("index.html", "about/index.html", "styles.css"), layout.batch().ownedPaths());
            assertEquals(List.of("/", "/about"), layout.routes().stream().map(BuildContract.Route::path).toList());
            assertEquals(6_000, layout.estimate().sourceCharacters());
            assertEquals(1, layout.units().size());
            assertTrue(calls.constructed().isEmpty());
            assertEquals(List.of("heading_0"), layout.batch().acceptanceIds());
        }
    }

    @Test
    void customContentKeepsItsApprovedDescriptionAndEveryAcceptanceStep() {
        var source = SpecFixtures.approved();
        var custom = new ApprovedSpec.Feature("content", "CUSTOM", "Bakery story and opening-hours content", List.of("home"), List.of());
        var spec = SpecFixtures.with(source, source.spec().pages(), List.of(), List.of(custom), source.spec().acceptance());
        var layout = planner.plan(spec, budget).orElseThrow();
        assertEquals("Solar Bakery", layout.acceptance().browser().getFirst().value());
        assertEquals("CUSTOM", spec.spec().features().getFirst().kind());
    }

    @Test
    void customInteractionRequiresPlanningAndIsNeverReinterpretedAsContent() {
        var source = SpecFixtures.approved();
        var custom = new ApprovedSpec.Feature("content", "CUSTOM", "Interactive form", List.of("home"), List.of());
        var scenario = new ApprovedSpec.Acceptance("form", "content", "BROWSER",
                List.of(new ApprovedSpec.Step("FILL", "input", "hello"), new ApprovedSpec.Step("ASSERT_TEXT", "h1", "Solar Bakery")), "Filled");
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), List.of(), List.of(custom), List.of(scenario)), budget).isEmpty());
    }

    @Test
    void routeCollisionsAndDynamicRoutesNeverUseTheStaticFastPath() {
        var source = twoPages(ApprovedSpec.RenderStack.STATIC);
        for (String path : List.of("/index.html", "/:slug")) {
            var pages = List.of(source.spec().pages().getFirst(), new ApprovedSpec.Page("about", path, "About", List.of("content")));
            assertTrue(planner.plan(SpecFixtures.with(source, pages, List.of(), source.spec().features(), source.spec().acceptance()), budget).isEmpty());
        }
        assertTrue(planner.plan(twoPages(ApprovedSpec.RenderStack.VITE), budget).isEmpty());
    }

    @Test
    void extraStaticPageStillNeedsPlanningWhenTheSameOutputBudgetWouldOverflow() {
        var source = twoPages(ApprovedSpec.RenderStack.STATIC);
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Bakery pages", List.of("home", "about", "contact"), List.of());
        var pages = List.of(source.spec().pages().getFirst(), source.spec().pages().get(1),
                new ApprovedSpec.Page("contact", "/contact", "Contact", List.of("content")));
        assertTrue(planner.plan(SpecFixtures.with(source, pages, List.of(), List.of(feature), source.spec().acceptance()), budget).isEmpty());
    }
}
