package com.code.pal.agent.contract;

import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApprovedProductScopeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String SCOPE = """
            {"requestedScope":{"goal":"Complete task workspace","pages":[{"path":"/"},
            {"path":"/tasks"},{"path":"/settings"}],"features":[{"id":"saved_tasks","description":"Real persistence"}]},
            "answersChanged":false,"acceptance":[]}
            """;

    private ContractPlanner.Input input(String draft) {
        var approved = new ApprovedProjectMemory.Approved(UUID.randomUUID(), 1, "{\"answers\":[]}", "Brief", draft);
        return new ContractPlanner.Input(ContractFixtures.ID, "Build the approved app",
                ApprovedProductScope.planningRequirements(approved, mapper), "map",
                new BuildDecisions.Choice(1, BuildDecisions.Storage.MERN, BuildDecisions.Auth.NONE, null),
                ContractFixtures.limits(), "client/src/App.tsx");
    }

    @Test
    void thePlannerReceivesCompletePagesFeaturesAndAnswerOverrides() {
        var requirements = mapper.readTree(input(SCOPE).approvedRequirements());
        assertEquals(3, requirements.path("requestedScope").path("pages").size());
        assertEquals("Real persistence", requirements.path("requestedScope").path("features").get(0).path("description").asString());
        assertFalse(requirements.has("acceptance"), "Scope is not duplicated with executable model assertions");
        var changed = input(SCOPE.replace("\"answersChanged\":false", "\"answersChanged\":true,\"answerOverrides\":[\"New product scope\"]"));
        assertTrue(changed.approvedRequirements().contains("New product scope"));
    }

    @Test
    void aOnePageContractCannotReplaceAnApprovedMultiPageProduct() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> ApprovedProductScope.requirePages(input(SCOPE), ContractFixtures.mern(), mapper));
        assertTrue(error.getMessage().contains("/tasks"));
    }

    @Test
    void fullRoutesPassAndChangedAnswersCannotEnforceAStalePagePlan() {
        var c = ContractFixtures.mern();
        var routes = List.of(new BuildContract.Route("/", "client/src/App.tsx", "ui"),
                new BuildContract.Route("/tasks", "client/src/App.tsx", "ui"),
                new BuildContract.Route("/settings", "client/src/App.tsx", "ui"));
        var complete = new BuildContract(c.schemaVersion(), c.id(), c.stack(), c.storage(), c.auth(), c.limits(), c.theme(),
                routes, c.types(), c.models(), c.api(), c.assets(), c.envNames(), c.units(), c.acceptance());
        assertDoesNotThrow(() -> ApprovedProductScope.requirePages(input(SCOPE), complete, mapper));
        assertDoesNotThrow(() -> ApprovedProductScope.requirePages(input(SCOPE.replace("false", "true")), c, mapper));
    }
}
