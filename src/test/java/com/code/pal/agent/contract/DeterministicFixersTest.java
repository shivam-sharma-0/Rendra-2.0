package com.code.pal.agent.contract;

import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicFixersTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeterministicFixers fixers = new DeterministicFixers(mapper);

    @Test
    void missingViteEntrypointUsesOnlyTheReviewedStarterAndNeverOverwritesCustomHtml() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        var snapshot = Map.of("src/main.tsx", "import './App'", "package.json", "{}");
        var fix = fixers.plan(contract, snapshot, Set.of("PREVIEW_ENTRYPOINT_MISSING"));
        assertTrue(fix.replacements().get("index.html").contains("/src/main.tsx"));
        assertTrue(fixers.plan(contract, Map.of("index.html", "custom"), Set.of("PREVIEW_ENTRYPOINT_MISSING")).replacements().isEmpty());
        assertTrue(fixers.plan(contract, Map.of(), Set.of("PREVIEW_ENTRYPOINT_MISSING")).replacements().isEmpty());
    }

    @Test
    void versionFixRequiresAnExactReviewedAliasAndPreservesCustomScriptsAndRanges() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        String original = "{\"scripts\":{\"build\":\"custom build\"},\"dependencies\":{\"react\":\"19.3\",\"custom\":\"^9.0.0\"}}";
        var fix = fixers.plan(contract, Map.of("package.json", original), Set.of("DEPENDENCY_VERSION_UNAVAILABLE"));
        var result = mapper.readTree(fix.replacements().get("package.json"));
        assertEquals("19.3.0", result.path("dependencies").path("react").asString());
        assertEquals("custom build", result.path("scripts").path("build").asString());
        assertEquals("^9.0.0", result.path("dependencies").path("custom").asString());
        assertTrue(fix.refreshLockfiles());
        assertTrue(fixers.plan(contract, Map.of("package.json", original.replace("19.3", "^19.0.0")),
                Set.of("DEPENDENCY_VERSION_UNAVAILABLE")).replacements().isEmpty());
    }

    @Test
    void lockMismatchRequestsARefreshWithoutDeletingOrInventingALock() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        var fix = fixers.plan(contract, Map.of("package.json", "{}", "package-lock.json", "old"), Set.of("LOCKFILE_MISMATCH"));
        assertTrue(fix.refreshLockfiles());
        assertTrue(fix.replacements().isEmpty());
        assertFalse(fixers.plan(contract, Map.of("package.json", "{}", "pnpm-lock.yaml", "custom"),
                Set.of("LOCKFILE_MISMATCH")).refreshLockfiles());
    }

    @Test
    void unknownDefectsAndStaticManifestsAreNeverSpeculativelyFixed() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.approved());
        var fix = fixers.plan(contract, Map.of("package.json", "{}"), Set.of("LOCKFILE_MISMATCH", "MISSING_LOGIN"));
        assertTrue(fix.replacements().isEmpty());
        assertFalse(fix.refreshLockfiles());
    }

    @Test
    void defectSignatureIsStableAndCannotBeAttemptedAgain() {
        var contract = new SpecPlanningTest().deterministic(SpecFixtures.browser());
        var input = Map.of("src/main.tsx", "entry");
        var fix = fixers.plan(contract, input, Set.of("PREVIEW_ENTRYPOINT_MISSING"));
        assertEquals(fix.signature(), fixers.plan(contract, input, Set.of("PREVIEW_ENTRYPOINT_MISSING")).signature());
        assertFalse(DeterministicFixers.canAttempt(fix, Set.of(fix.signature()), 0));
        assertFalse(DeterministicFixers.canAttempt(fix, Set.of(), 3));
        assertTrue(DeterministicFixers.canAttempt(fix, Set.of(), 0));
    }
}
