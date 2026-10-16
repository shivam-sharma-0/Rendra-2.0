package com.code.pal.agent.contract;

import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.RepairPatches;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RepairCreationTest {
    private final RepairPatches patches = new RepairPatches(new ObjectMapper());
    private final String revision = "sha256:" + "a".repeat(64);

    private RepairPatch patch(String path, String version, List<RepairPatch.Edit> edits) {
        return new RepairPatch(1, "repair", revision, List.of(new RepairPatch.FilePatch(path, version, edits)));
    }

    @Test
    void anAttributedAbsentOwnedFileCanBeCreatedWithOneExplicitEmptySearch() {
        var scope = new RepairPatches.Scope("repair", revision, Set.of("src/App.tsx"),
                Map.of("src/App.tsx", new UnitGenerator.FileView("absent", null)));
        var result = patches.prepare(scope, patch("src/App.tsx", "absent", List.of(new RepairPatch.Edit("", "export default () => <main>Tasks</main>"))));
        assertEquals("absent", result.getFirst().expectedVersion());
        assertEquals("export default () => <main>Tasks</main>", result.getFirst().content());
    }

    @Test
    void creationCannotWidenScopeOrUseAnExistingOrUnprovedSnapshot() {
        var absent = new RepairPatches.Scope("repair", revision, Set.of("src/App.tsx"),
                Map.of("src/App.tsx", new UnitGenerator.FileView("absent", null)));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(absent,
                patch("src/Foreign.tsx", "absent", List.of(new RepairPatch.Edit("", "new")))));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(absent,
                patch("src/App.tsx", "absent", List.of(new RepairPatch.Edit("", "new"), new RepairPatch.Edit("", "more")))));
        var existing = new RepairPatches.Scope("repair", revision, Set.of("src/App.tsx"),
                Map.of("src/App.tsx", new UnitGenerator.FileView("sha256:" + ContractStore.hash(""), "")));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(existing,
                patch("src/App.tsx", "sha256:" + ContractStore.hash(""), List.of(new RepairPatch.Edit("", "new")))));
    }
}
