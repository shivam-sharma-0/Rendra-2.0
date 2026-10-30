package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractScaffoldRouterTest {
    static BuildContract routed() {
        var original = ContractFixtures.vite();
        var owner = new BuildContract.Unit("ui", "Two pages", List.of("src/Home.tsx", "src/Settings.tsx"), List.of(), List.of(), List.of("ui"));
        var layout = new BuildContract(1, original.id(), original.stack(), original.storage(), original.auth(), original.limits(), original.theme(),
                List.of(new BuildContract.Route("/", "src/Home.tsx", "ui"), new BuildContract.Route("/settings", "src/Settings.tsx", "ui")),
                original.types(), original.models(), original.api(), original.assets(), original.envNames(), List.of(owner), original.acceptance());
        return ContractPlans.bind(layout, SpecFixtures.browser(), List.of(new ContractBatch("app", List.of("ui"), List.of(),
                owner.ownedPaths(), List.of(), owner.acceptanceIds(), 32_000, 6_000, 4_000)));
    }

    @Test
    void v2BootstrapUsesReactiveHashRoutingAndPrefixSafeHomeLink() {
        var contract = routed();
        String app = new ContractScaffold(new ObjectMapper()).render(contract, "Fixture", path -> null).get("src/App.tsx");
        assertTrue(app.contains("window.location.hash"));
        assertTrue(app.contains("<HashRouter>") && app.contains("<Routes>"), "Use the preinstalled reactive hash router");
        // The shell names the app and links the plain pages (2026-10-29 review: pages had no header or navigation).
        assertTrue(app.contains("const APP_NAME = \"Fixture\"") && app.contains("className=\"app-brand\">{APP_NAME}"));
        assertTrue(app.contains("className=\"app-nav\"") && app.contains("!route.path.includes(':')"));
        assertTrue(app.contains("useParams()"), "Keep dynamic route params available to generated pages");
        assertTrue(app.contains("replaceState"), "The initial home hash must replace history, not add a duplicate entry");
        assertTrue(app.contains("<Link to=\"/\""));
        assertFalse(app.contains("window.location.pathname"));
    }

    @Test
    void aFreshV1BootstrapAlsoUsesTheHashRouterItsPromptsLinkTo() {
        // Live E2E 2026-10-27: a v1 MERN app got BrowserRouter, so the units' #/path edit links never left Home.
        var v2 = routed();
        var v1 = new BuildContract(1, v2.id(), v2.stack(), v2.storage(), v2.auth(), v2.limits(), v2.theme(),
                v2.routes(), v2.types(), v2.models(), v2.api(), v2.assets(), v2.envNames(), v2.units(), v2.acceptance());
        String app = new ContractScaffold(new ObjectMapper()).render(v1, "Fixture", path -> null).get("src/App.tsx");
        assertTrue(app.contains("<HashRouter>") && app.contains("<Routes>"));
        assertFalse(app.contains("BrowserRouter"));
    }

    @Test
    void viteGenerationAndRepairPolicyUseHashLinks() {
        String policy = ContractImports.policy(routed());
        assertTrue(policy.contains("href=\"#/path\""));
        assertFalse(policy.contains("href=\"/path\""));
    }

    @ParameterizedTest
    @CsvSource({"'/settings',true", "'#/settings',true", "'/settings',false", "'#/settings',false"})
    void viteAcceptanceUsesHashLinksButKeepsLogicalRouteValues(String href, boolean form) {
        var original = SpecFixtures.approved();
        var pages = List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content")),
                new ApprovedSpec.Page("settings", "/settings", "Settings", List.of("content")));
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Settings form", List.of("home", "settings"), List.of());
        var steps = new ArrayList<>(List.of(
                new ApprovedSpec.Step("CLICK", "a[href='" + href + "']", null),
                new ApprovedSpec.Step("ASSERT_ROUTE", null, "/settings")));
        if (form) {
            steps.add(new ApprovedSpec.Step("FILL", "#name", "Visitor"));
        }
        var scenario = new ApprovedSpec.Acceptance("settings", "content", "BROWSER", steps, "Settings can be edited");
        var body = SpecFixtures.with(original, pages, List.of(), List.of(feature), List.of(scenario)).spec();
        var approved = SpecFixtures.envelope(body, new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), ApprovedSpec.Readiness.READY);
        var actions = SpecPlanValues.acceptance(approved).browser();
        assertTrue(actions.getFirst().selector().contains("[href=\"#/settings\"]"));
        assertEquals(form ? BrowserKind.CLICK : BrowserKind.ASSERT_COUNT, actions.getFirst().kind());
        if (form) {
            assertEquals("/settings", actions.get(1).value());
        }
        assertEquals("/settings", approved.spec().acceptance().getFirst().steps().get(1).value());
        assertEquals("a[href='" + href + "']", approved.spec().acceptance().getFirst().steps().getFirst().selector());
    }

    @Test
    void generatedBootstrapHasARealNotFoundViewWithoutReplacingAnExistingApp() {
        var contract = routed();
        var scaffold = new ContractScaffold(new ObjectMapper());
        var output = scaffold.render(contract, "Fixture", path -> null);
        assertTrue(output.get("src/App.tsx").contains("Page not found"));
        assertTrue(output.get(contract.clientRoot() + "routes.ts").contains("/settings"));
        assertFalse(scaffold.render(contract, "Fixture", path -> path.equals("src/App.tsx") ? "existing custom router" : null)
                .containsKey("src/App.tsx"));
    }
}
