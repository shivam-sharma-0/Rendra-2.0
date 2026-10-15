package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestRouterScopeTest {
    @Test
    void explicitInspectionAndPlanReduceBuildAuthority() {
        for (String text : List.of("Don't change anything, explain the backend", "changes nahi krna, sirf check",
                "just explain how to build an app", "mat banao, sirf samjhao")) {
            assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.BUILD, text, List.of()), text);
        }
        for (String text : List.of("Only plan how to build the app", "sirf plan banao", "pehle plan")) {
            assertEquals(ProjectType.PLAN, RequestRouter.mode(ProjectType.BUILD, text, List.of()), text);
        }
        assertEquals(ProjectType.PLAN, RequestRouter.mode(ProjectType.PLAN, "implement login", List.of()));
        assertEquals(ProjectType.CHAT, RequestRouter.mode(ProjectType.CHAT, "build an app", List.of()));
    }

    @Test
    void scopedNegationDoesNotHideTheRequestedEdit() {
        assertEquals(ProjectType.BUILD,
                RequestRouter.mode(ProjectType.BUILD, "Don't change the theme but add a button", List.of()));
        assertEquals(ProjectType.BUILD,
                RequestRouter.mode(ProjectType.BUILD, "just explain the error but fix the handler", List.of()));
        assertEquals(RequestRouter.Route.CONTINUE, RequestRouter.route("haan", List.of(), true));
        assertEquals(RequestRouter.Route.SMALL_TALK, RequestRouter.route("haan", List.of(), false));
    }

    @Test
    void clearHinglishAndEnglishRequestsAreChangesWithoutAModel() {
        for (String text : List.of("calculator banao", "header ka color badlo", "app bana do", "a simple calculator")) {
            assertEquals(RequestRouter.Route.CHANGE, RequestRouter.route(text), text);
        }
        assertEquals(RequestRouter.Route.SMALL_TALK, RequestRouter.route("namaste"));
    }
}
