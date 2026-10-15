package com.code.pal.project.requirement;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequirementProjectionTest {
    @Test void updatesOnlyTheOwnedBlockAndPreservesUserNotes() {
        String first = RequirementProjection.renderBrief("# User notes\nKeep the blue logo.\n", "Old decisions", 1);
        first += "\n## Notes\nKeep keyboard support.\n";
        String changed = RequirementProjection.renderBrief(first, "Corrected decisions", 2);
        assertTrue(changed.contains("Keep the blue logo.") && changed.contains("Keep keyboard support."));
        assertTrue(changed.contains("Corrected decisions") && changed.contains("revision 2"));
        assertFalse(changed.contains("Old decisions") || changed.contains("revision 1"));
        assertEquals(changed, RequirementProjection.renderBrief(changed, "Corrected decisions", 2));
    }
    @Test void ambiguousMarkersAreNeverOverwritten() {
        for (String malformed : java.util.List.of(RequirementProjection.BEGIN, RequirementProjection.END,
                RequirementProjection.END + RequirementProjection.BEGIN, RequirementProjection.BEGIN + RequirementProjection.BEGIN + RequirementProjection.END)) {
            assertThrows(IllegalStateException.class, () -> RequirementProjection.renderBrief(malformed, "New", 1));
        }
    }
    @Test void userTextCannotInsertExtraOwnedMarkers() {
        String first = RequirementProjection.renderBrief("", "User says " + RequirementProjection.BEGIN + RequirementProjection.END, 1);
        assertDoesNotThrow(() -> RequirementProjection.renderBrief(first, "Updated", 2));
    }
}
