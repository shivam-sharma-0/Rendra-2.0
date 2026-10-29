package com.code.pal.agent.activity;

import java.util.List;

/**
 * What a timeline step means to the user: "Created src/App.tsx", "Checked types", "Saved 4 files".
 * Derived from a stored step by {@link AgentActivityMapper}; never persisted, so the same mapping
 * applies to old runs.
 *
 * @param kind  what happened
 * @param title one short line for the timeline
 * @param paths project-relative files it concerns (empty when none)
 * @param ok    {@code true} / {@code false} for an outcome, {@code null} while in progress or when
 *              there is no outcome
 * @param detail optional second line (a failure message, a problem count)
 * @param items optional list under the line (plan steps)
 */
public record AgentActivity(Kind kind, String title, List<String> paths, Boolean ok, String detail, List<String> items) {

    public AgentActivity {
        paths = paths == null ? List.of() : List.copyOf(paths);
        items = items == null ? List.of() : List.copyOf(items);
    }

    public enum Kind {
        THINKING,
        /** Read, listed or searched files. */
        READ,
        CREATE,
        EDIT,
        DELETE,
        /** Renamed or copied a file. */
        MOVE,
        PLAN,
        /** Typecheck, build, tests, validation — running ({@code ok == null}) or finished. */
        CHECK,
        /** The review pass over a first version. */
        REVIEW,
        ATTACHMENTS,
        /** The change set was committed. */
        SAVED,
        PREVIEW,
        /** Queued, restored, recovered. */
        STATUS,
        /** A stage of a structured build (planning, writing a page, checks, repair); it runs until the next step. */
        PROGRESS,
        STOPPED,
        FAILED,
        ASK_USER,
        MESSAGE
    }
}
