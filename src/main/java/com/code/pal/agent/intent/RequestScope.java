package com.code.pal.agent.intent;

import com.code.pal.project.domain.ProjectType;
import java.util.List;

/** Compatibility helpers for sizing/starters; all vocabulary lives in RequestRouter. */
public final class RequestScope {
    private RequestScope() {}

    public static String normalize(String text) {
        return RequestRouter.normalize(text);
    }

    public static ProjectType mode(ProjectType requested, String request) {
        return RequestRouter.mode(requested, request, List.of());
    }

    public static boolean continuation(String request, ProjectType mode, boolean pendingProposal) {
        return mode != ProjectType.CHAT
                && RequestRouter.route(request, List.of(), pendingProposal) == RequestRouter.Route.CONTINUE;
    }

    public static boolean action(String request) {
        return RequestRouter.route(request) == RequestRouter.Route.CHANGE;
    }

    public static String positiveSignals(String text) {
        return RequestRouter.positiveSignals(text);
    }
}