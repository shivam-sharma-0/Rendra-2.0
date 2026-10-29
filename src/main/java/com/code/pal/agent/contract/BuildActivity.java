package com.code.pal.agent.contract;

import java.util.List;
import java.util.function.Consumer;

/** A structured build's user-visible stages. A plain {@code Consumer<String>} receives only the titles. */
public interface BuildActivity extends Consumer<String> {

    /** One stage covering several parts done together, e.g. the units written by one batch call. */
    default void stage(String title, List<String> items) {
        accept(title);
    }

    /** A check that found a problem: shown as failed, with one short detail line. */
    default void problem(String title, String detail) {
        accept(title);
    }

    static BuildActivity of(Consumer<String> activity) {
        return activity instanceof BuildActivity stages ? stages : activity::accept;
    }
}
