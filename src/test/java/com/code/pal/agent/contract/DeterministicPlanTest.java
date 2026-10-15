package com.code.pal.agent.contract;

import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

class DeterministicPlanTest {
    private final DeterministicPlan planner = new DeterministicPlan(new ObjectMapper());
    private final BatchBudget.Limits limits = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);

    @Test
    void eligibleStaticSpecNeedsNoPlannerCallAndProducesOneOwnedBatch() {
        try (var modelPlanner = mockConstruction(ContractPlanner.class)) {
            var layout = planner.plan(SpecFixtures.approved(), limits).orElseThrow();
            assertTrue(modelPlanner.constructed().isEmpty());
            assertEquals(List.of("index.html", "styles.css"), layout.batch().ownedPaths());
            assertEquals(1, layout.units().size());
            assertEquals(layout.units().getFirst().ownedPaths(), layout.batch().ownedPaths());
            assertFalse(layout.acceptance().browser().isEmpty());
            assertEquals(SpecFixtures.approved().specHash(), layout.specHash());
        }
    }

    @Test
    void browserLayoutPreservesStorageChoiceAndUsesStableLocalStatePaths() {
        var layout = planner.plan(SpecFixtures.browser(), limits).orElseThrow();
        assertEquals(ApprovedSpec.Storage.BROWSER, layout.storage());
        assertEquals(List.of("src/App.tsx", "src/index.css", "src/local-state.ts"), layout.batch().ownedPaths());
        assertEquals(List.of("app"), layout.batch().unitIds());
    }

    @Test
    void equivalentFeatureOrderingProducesTheSameLayout() {
        var source = features(SpecFixtures.approved(), 6, "CONTENT");
        var reverse = new ArrayList<>(source.spec().features());
        Collections.reverse(reverse);
        var equivalent = SpecFixtures.with(source, source.spec().pages(), source.spec().entities(), reverse, source.spec().acceptance());
        assertEquals(planner.plan(source, limits), planner.plan(equivalent, limits));
    }

    @Test
    void sixFeaturesAreEligibleAndSevenOrUnknownKindsRequireThePlanner() {
        assertTrue(planner.plan(features(SpecFixtures.approved(), 6, "CONTENT"), limits).isPresent());
        assertTrue(planner.plan(features(SpecFixtures.approved(), 7, "CONTENT"), limits).isEmpty());
        assertTrue(planner.plan(features(SpecFixtures.approved(), 1, "FUTURE_FEATURE"), limits).isEmpty());
    }

    @Test
    void eightScalarFieldsAreEligibleButNinthOrJsonOrSecondEntityNeedsPlanning() {
        var source = SpecFixtures.browser();
        assertTrue(planner.plan(fields(source, 8, "STRING"), limits).isPresent());
        assertTrue(planner.plan(fields(source, 9, "STRING"), limits).isEmpty());
        assertTrue(planner.plan(fields(source, 1, "JSON"), limits).isEmpty());
        var entities = new ArrayList<>(source.spec().entities());
        entities.add(new ApprovedSpec.Entity("other", List.of(new ApprovedSpec.Field("name", "STRING", true)),
                ApprovedSpec.Storage.BROWSER, "USER"));
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), entities,
                source.spec().features(), source.spec().acceptance()), limits).isEmpty());
    }

    @Test
    void eightScenariosAndTwentyFourActionsAreTheExactLimits() {
        assertTrue(planner.plan(checks(8, 3), limits).isPresent());
        assertTrue(planner.plan(checks(9, 1), limits).isEmpty());
        assertTrue(planner.plan(checks(5, 5), limits).isEmpty());
    }

    @Test
    void onlyOneRootPageWithoutRemoteOrUnknownMappingQualifies() {
        var source = SpecFixtures.approved();
        var pages = List.of(new ApprovedSpec.Page("home", "/about", "Home", List.of("content")));
        assertTrue(planner.plan(SpecFixtures.with(source, pages, List.of(), source.spec().features(), source.spec().acceptance()), limits).isEmpty());
        var cloud = SpecFixtures.envelope(source.spec(), new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.SUPABASE, ApprovedSpec.Auth.ACCOUNTS), ApprovedSpec.Readiness.CONNECTION_REQUIRED);
        assertTrue(planner.plan(cloud, limits).isEmpty());
        assertTrue(planner.plan(source, new BatchBudget.Limits(32_000, 1_024, 32_000, 0, true)).isEmpty());
    }

    @Test
    void unsupportedAcceptanceIsNotSilentlyDropped() {
        var source = SpecFixtures.approved();
        var check = new ApprovedSpec.Acceptance("heading", "content", "BROWSER",
                List.of(new ApprovedSpec.Step("RUN_JAVASCRIPT", "body", "unsafe")), "Visible page");
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), List.of(),
                source.spec().features(), List.of(check)), limits).isEmpty());
    }

    @Test
    void runtimeApiAcceptanceAndSharedBrowserOwnershipCannotUseTheFastPath() {
        var source = SpecFixtures.browser();
        var entity = source.spec().entities().getFirst();
        var shared = new ApprovedSpec.Entity(entity.id(), entity.fields(), entity.persistence(), "SHARED");
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), List.of(shared),
                source.spec().features(), source.spec().acceptance()), limits).isEmpty());
        var scenario = source.spec().acceptance().getFirst();
        var api = new ApprovedSpec.Acceptance(scenario.id(), scenario.featureId(), "API", scenario.steps(), scenario.expected());
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), source.spec().entities(),
                source.spec().features(), List.of(api)), limits).isEmpty());
    }

    @Test
    void incompletePageOrCrudEntityMappingRequiresThePlanner() {
        var source = SpecFixtures.approved();
        var page = new ApprovedSpec.Page("home", "/", "Home", List.of());
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Unmapped content", List.of(), List.of());
        assertTrue(planner.plan(SpecFixtures.with(source, List.of(page), List.of(), List.of(feature),
                source.spec().acceptance()), limits).isEmpty());
        source = SpecFixtures.browser();
        var crud = new ApprovedSpec.Feature("tasks", "LOCAL_CRUD", "Unmapped tasks", List.of("home"), List.of());
        assertTrue(planner.plan(SpecFixtures.with(source, source.spec().pages(), List.of(), List.of(crud),
                source.spec().acceptance()), limits).isEmpty());
    }

    private ApprovedSpec features(ApprovedSpec source, int count, String kind) {
        var features = new ArrayList<ApprovedSpec.Feature>();
        for (int index = 0; index < count; index++) {
            features.add(new ApprovedSpec.Feature("f" + index, kind, "Content " + index, List.of("home"), List.of()));
        }
        var page = new ApprovedSpec.Page("home", "/", "Home", features.stream().map(ApprovedSpec.Feature::id).toList());
        var checks = features.stream().map(feature -> new ApprovedSpec.Acceptance("check" + feature.id(), feature.id(),
                "BROWSER", List.of(new ApprovedSpec.Step("ASSERT_TEXT", "h1", "Solar Bakery")), "Heading is visible")).toList();
        return SpecFixtures.with(source, List.of(page), List.of(), features, checks);
    }

    private ApprovedSpec fields(ApprovedSpec source, int count, String kind) {
        var fields = new ArrayList<ApprovedSpec.Field>();
        for (int index = 0; index < count; index++) {
            fields.add(new ApprovedSpec.Field("field" + index, kind, true));
        }
        var entity = new ApprovedSpec.Entity("task", fields, ApprovedSpec.Storage.BROWSER, "USER");
        return SpecFixtures.with(source, source.spec().pages(), List.of(entity), source.spec().features(), source.spec().acceptance());
    }

    private ApprovedSpec checks(int scenarios, int actionsPerScenario) {
        var source = SpecFixtures.approved();
        var checks = new ArrayList<ApprovedSpec.Acceptance>();
        for (int index = 0; index < scenarios; index++) {
            var steps = new ArrayList<ApprovedSpec.Step>();
            for (int action = 0; action < actionsPerScenario; action++) {
                steps.add(new ApprovedSpec.Step("ASSERT_TEXT", "h1", "Solar Bakery"));
            }
            checks.add(new ApprovedSpec.Acceptance("check" + index, "content", "BROWSER", steps, "Heading is visible"));
        }
        return SpecFixtures.with(source, source.spec().pages(), List.of(), source.spec().features(), checks);
    }
}
