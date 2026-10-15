package com.code.pal.project.spec;

import java.util.List;
import java.util.UUID;

/** Synthetic specs shared only by the new pure planning tests. */
public final class SpecFixtures {
    public static final String HASH = "sha256:" + "a".repeat(64);

    private SpecFixtures() {}

    public static ApprovedSpec approved() {
        var feature = new ApprovedSpec.Feature("content", "CONTENT", "Bakery introduction", List.of("home"), List.of());
        var acceptance = new ApprovedSpec.Acceptance("heading", "content", "BROWSER",
                List.of(new ApprovedSpec.Step("ASSERT_TEXT", "h1", "Solar Bakery")), "Solar Bakery heading is visible");
        var spec = new ApprovedSpec.Spec("Solar Bakery", "en", "A bakery page",
                List.of(new ApprovedSpec.Page("home", "/", "Home", List.of("content"))), List.of(), List.of(feature),
                new ApprovedSpec.Theme("#fff", "#111", "#f80", "#888", "8px", "8px", "system"),
                List.of(acceptance), List.of(), new ApprovedSpec.Constraints(true, true, List.of()));
        return envelope(spec, new ApprovedSpec.Desired(ApprovedSpec.RenderStack.STATIC,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), ApprovedSpec.Readiness.READY);
    }

    public static ApprovedSpec envelope(ApprovedSpec.Spec spec, ApprovedSpec.Desired desired, ApprovedSpec.Readiness readiness) {
        return new ApprovedSpec(1, new UUID(1, 2), 1, HASH, new UUID(3, 4), new UUID(5, 6), 0,
                ApprovedSpec.Status.APPROVED, HASH, desired, readiness, spec);
    }

    public static ApprovedSpec with(ApprovedSpec original, List<ApprovedSpec.Page> pages,
            List<ApprovedSpec.Entity> entities, List<ApprovedSpec.Feature> features,
            List<ApprovedSpec.Acceptance> acceptance) {
        var s = original.spec();
        var spec = new ApprovedSpec.Spec(s.title(), s.language(), s.goal(), pages, entities, features,
                s.theme(), acceptance, s.assets(), s.constraints());
        return envelope(spec, original.desired(), original.readiness());
    }

    public static ApprovedSpec browser() {
        var original = approved();
        var entity = new ApprovedSpec.Entity("task", List.of(new ApprovedSpec.Field("title", "STRING", true)),
                ApprovedSpec.Storage.BROWSER, "USER");
        var feature = new ApprovedSpec.Feature("tasks", "LOCAL_CRUD", "Manage tasks", List.of("home"), List.of("task"));
        var s = original.spec();
        var spec = new ApprovedSpec.Spec(s.title(), s.language(), "Manage personal tasks",
                List.of(new ApprovedSpec.Page("home", "/", "Tasks", List.of("tasks"))), List.of(entity), List.of(feature),
                s.theme(), List.of(new ApprovedSpec.Acceptance("tasksVisible", "tasks", "BROWSER",
                        List.of(new ApprovedSpec.Step("ASSERT_COUNT", "[data-task]", "0")), "Empty task list is visible")),
                List.of(), s.constraints());
        return envelope(spec, new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.BROWSER, ApprovedSpec.Auth.NONE), ApprovedSpec.Readiness.READY);
    }
}
