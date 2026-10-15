package com.code.pal.agent.route;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SizeTierRulesTest {

    private static SizeTier newBuild(String request) {
        return SizeTierRules.decide(ProjectType.BUILD, request, null, false).tier();
    }

    @Test
    void smallSitesAndToolsStaySmall() {
        assertEquals(SizeTier.S, newBuild("Create a landing page for my coffee shop"));
        assertEquals(SizeTier.S, newBuild("A simple todo list"));
        assertEquals(SizeTier.S, newBuild("Make an app about dogs and cats"), "'about' alone is not an About page");
    }

    @Test
    void afewPagesAreMediumAndPagesWithDataAreLarge() {
        assertEquals(SizeTier.M, newBuild("A portfolio website with about us, gallery and contact pages"));
        assertEquals(SizeTier.M, newBuild("A restaurant site with five pages"));
        assertEquals(SizeTier.L, newBuild("An online store with a catalog, cart, checkout, login and an admin dashboard"));
        assertEquals(SizeTier.L, newBuild("A full-stack booking platform where users sign up and pay with Stripe"));
    }

    @Test
    void anApprovedRequirementsPromptIsJudgedByItsAnswersAndTheFirstPrompt() {
        // The questions mention a database and sign-in; the answers say neither is wanted.
        String approved = """
                Build the requested project now. Use the approved requirements below as the source of truth.

                Approved requirements:
                - Where should the data be stored in the database?: None (session only)
                - Do users need to sign in with an account?: No
                - What should the visual style be?: Clean & minimal
                """;
        SizeTierRules.Decision decision = SizeTierRules.decide(ProjectType.BUILD, approved,
                "Build a single-page tip calculator with tip buttons.", false);
        assertEquals(SizeTier.S, decision.tier(), decision.reasons().toString());

        String withData = approved.replace("None (session only)", "Saved database (Cloud)").replace(": No\n", ": Yes, email sign-in\n");
        assertEquals(SizeTier.M, SizeTierRules.decide(ProjectType.BUILD, withData,
                "A habit tracker with a dashboard", false).tier());
    }

    @Test
    void changesToAnExistingProjectAreSmallUnlessTheyAddSeveralPages() {
        assertEquals(SizeTier.S, SizeTierRules.decide(ProjectType.BUILD, "Make the header blue", null, true).tier());
        assertEquals(SizeTier.S, SizeTierRules.decide(ProjectType.BUILD, "Add a login page", null, true).tier());
        assertEquals(SizeTier.M, SizeTierRules.decide(ProjectType.BUILD,
                "Add dashboard, reports and settings pages", null, true).tier());
        assertEquals(SizeTier.L, SizeTierRules.decide(ProjectType.BUILD,
                "Rebuild it from scratch as an online store with catalog, cart, checkout and login", null, true).tier());
    }

    @Test
    void chatAndPlanRunsAreAlwaysSmall() {
        assertEquals(SizeTier.S, SizeTierRules.decide(ProjectType.CHAT, "Explain the full-stack platform with login", null, false).tier());
        assertEquals(SizeTier.S, SizeTierRules.decide(ProjectType.PLAN, "Plan an e-commerce marketplace", null, false).tier());
    }

    @Test
    void reasonsNameTheSignals() {
        List<String> reasons = SizeTierRules.decide(ProjectType.BUILD, "An online store with a cart and login", null, false).reasons();
        assertEquals("new project", reasons.getFirst());
        assertTrue(reasons.toString().contains("cart") && reasons.toString().contains("online store"), reasons.toString());
    }

    @Test
    void mediumAndLargeRunsGetTheLargerLimitsAndNeverLess() {
        AgentProperties props = new AgentProperties();
        RunSize small = RunSize.of(SizeTier.S, List.of(), props);
        assertEquals(300_000, small.timeoutMs());
        assertEquals(40, small.maxIterations());
        RunSize large = RunSize.of(SizeTier.L, List.of(), props);
        assertEquals(900_000, large.timeoutMs());
        assertEquals(60, large.maxIterations());
        assertEquals(150, large.maxToolCalls());

        props.setRunTimeoutMs(1_200_000);
        props.setMaxIterations(80);
        RunSize medium = RunSize.of(SizeTier.M, List.of(), props);
        assertEquals(1_200_000, medium.timeoutMs(), "a larger S limit also holds for M");
        assertEquals(80, medium.maxIterations());
        assertEquals(1_200_000, props.longestRunTimeoutMs());
    }

    @Test
    void aLongerTimeoutKeepsTheTierAndBudgets() {
        AgentProperties props = new AgentProperties();
        RunSize small = RunSize.of(SizeTier.S, List.of("new project"), props);
        RunSize first = small.withLongerTimeout(900_000, "first build installs dependencies");
        assertEquals(SizeTier.S, first.tier());
        assertEquals(900_000, first.timeoutMs());
        assertEquals(small.maxIterations(), first.maxIterations());
        assertEquals(List.of("new project", "first build installs dependencies"), first.reasons());
        assertEquals(small, small.withLongerTimeout(60_000, "shorter"), "never shortens");
    }
    @Test void negatedCapabilitiesAndActualBackendFeaturesHaveDifferentScope() {
        assertEquals(SizeTier.S,newBuild("ek page calculator banao without backend, database or login"));
        assertEquals(SizeTier.S,newBuild("simple timer banao, database nahi chahiye"));
        assertEquals(SizeTier.M,SizeTierRules.decide(ProjectType.BUILD,"signup authentication aur crud jodo",null,true).tier());
        var choice=new com.code.pal.project.capability.BuildDecisions.Choice(1,com.code.pal.project.capability.BuildDecisions.Storage.MERN,com.code.pal.project.capability.BuildDecisions.Auth.ACCOUNTS,null);
        assertEquals(SizeTier.M,SizeTierRules.decide(ProjectType.BUILD,"simple calculator",null,false,choice).tier());
        assertEquals(SizeTier.S,SizeTierRules.decide(ProjectType.BUILD,"change header color",null,true,choice).tier());
    }
}
