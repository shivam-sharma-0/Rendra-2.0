package com.code.pal.project.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovedSpecTest {
    @Test
    void frozenEnvelopeRoundTripsWithoutLosingApprovalIdentity() {
        var mapper = new ObjectMapper();
        var expected = SpecFixtures.browser();
        assertEquals(expected, mapper.readValue(mapper.writeValueAsString(expected), ApprovedSpec.class));
    }

    @Test
    void unsupportedVersionAndInvalidRevisionsOrHashesAreRejected() {
        var s = SpecFixtures.approved();
        assertThrows(IllegalArgumentException.class, () -> new ApprovedSpec(2, s.specId(), 1, s.specHash(),
                s.projectId(), s.requirementsProjection(), 0, s.status(), s.capabilityRevision(), s.desired(), s.readiness(), s.spec()));
        assertThrows(IllegalArgumentException.class, () -> new ApprovedSpec(1, s.specId(), 0, "not-a-hash",
                s.projectId(), s.requirementsProjection(), 0, s.status(), s.capabilityRevision(), s.desired(), s.readiness(), s.spec()));
        assertThrows(IllegalArgumentException.class, () -> new ApprovedSpec(1, s.specId(), 1, "not-a-hash",
                s.projectId(), s.requirementsProjection(), 0, s.status(), s.capabilityRevision(), s.desired(), s.readiness(), s.spec()));
    }

    @Test
    void featureAndAcceptanceReferencesMustResolve() {
        var s = SpecFixtures.approved();
        var missing = new ApprovedSpec.Feature("content", "CONTENT", "Hello", List.of("missing"), List.of());
        assertThrows(IllegalArgumentException.class, () -> SpecFixtures.with(s, s.spec().pages(), List.of(),
                List.of(missing), s.spec().acceptance()));
        var acceptance = new ApprovedSpec.Acceptance("wrong", "missing", "BROWSER",
                List.of(new ApprovedSpec.Step("ASSERT_TEXT", "h1", "Hello")), "Hello is visible");
        assertThrows(IllegalArgumentException.class, () -> SpecFixtures.with(s, s.spec().pages(), List.of(),
                s.spec().features(), List.of(acceptance)));
    }

    @Test
    void listsAreImmutableAndDuplicateIdentitiesAreRejected() {
        var s = SpecFixtures.approved();
        var pages = new ArrayList<>(s.spec().pages());
        var copy = SpecFixtures.with(s, pages, List.of(), s.spec().features(), s.spec().acceptance());
        pages.clear();
        assertEquals(1, copy.spec().pages().size());
        assertThrows(UnsupportedOperationException.class, () -> copy.spec().pages().clear());
        assertThrows(IllegalArgumentException.class, () -> SpecFixtures.with(s,
                List.of(s.spec().pages().getFirst(), s.spec().pages().getFirst()), List.of(),
                s.spec().features(), s.spec().acceptance()));
    }

    @Test
    void cloudIntentCanBeApprovedButDisconnectedWithoutBrowserSubstitution() {
        var base = SpecFixtures.approved();
        var desired = new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.SUPABASE, ApprovedSpec.Auth.ACCOUNTS);
        var spec = SpecFixtures.envelope(base.spec(), desired, ApprovedSpec.Readiness.CONNECTION_REQUIRED);
        assertEquals(ApprovedSpec.Storage.SUPABASE, spec.desired().storage());
        assertEquals(ApprovedSpec.Readiness.CONNECTION_REQUIRED, spec.readiness());
        assertThrows(IllegalArgumentException.class, () -> new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.BROWSER, ApprovedSpec.Auth.ACCOUNTS));
    }

    @Test
    void fieldAndScenarioCapsAndUtf8EnvelopeCapacityAreEnforced() {
        var fields = new ArrayList<ApprovedSpec.Field>();
        for (int index = 0; index < 25; index++) {
            fields.add(new ApprovedSpec.Field("field" + index, "STRING", false));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ApprovedSpec.Entity("task", fields, ApprovedSpec.Storage.BROWSER, "USER"));
        var s = SpecFixtures.approved();
        var scenarios = new ArrayList<ApprovedSpec.Acceptance>();
        for (int index = 0; index < 25; index++) {
            scenarios.add(new ApprovedSpec.Acceptance("scenario" + index, "content", "BROWSER",
                    s.spec().acceptance().getFirst().steps(), "Visible heading"));
        }
        assertThrows(IllegalArgumentException.class, () -> SpecFixtures.with(s, s.spec().pages(),
                s.spec().entities(), s.spec().features(), scenarios));
        var features = new ArrayList<ApprovedSpec.Feature>();
        for (int index = 0; index < 24; index++) {
            features.add(new ApprovedSpec.Feature("f" + index, "CONTENT", "界".repeat(1_000), List.of("home"), List.of()));
        }
        var page = new ApprovedSpec.Page("home", "/", "Home", features.stream().map(ApprovedSpec.Feature::id).toList());
        var checks = features.stream().map(feature -> new ApprovedSpec.Acceptance("check" + feature.id(), feature.id(),
                "BROWSER", s.spec().acceptance().getFirst().steps(), "Visible heading")).toList();
        var failure = assertThrows(IllegalArgumentException.class,
                () -> SpecFixtures.with(s, List.of(page), List.of(), features, checks));
        assertTrue(failure.getMessage().contains("UTF-8 capacity"));
    }
}
