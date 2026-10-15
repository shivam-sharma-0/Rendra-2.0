package com.code.pal.agent.contract;

import com.code.pal.agent.repair.RepairPatch;
import com.code.pal.agent.repair.RepairPatches;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class RepairPatchTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RepairPatches patches = new RepairPatches(mapper);
    private final String revision = "sha256:" + "d".repeat(64);

    private String version(String text) { return "sha256:" + ContractStore.hash(text); }

    private RepairPatch patch(String text, List<RepairPatch.Edit> edits) {
        return new RepairPatch(1, "app", revision, List.of(new RepairPatch.FilePatch("src/App.tsx", version(text), edits)));
    }

    private RepairPatches.Scope scope(Map<String, String> bodies) {
        return new RepairPatches.Scope("app", revision, Set.copyOf(bodies.keySet()), bodies.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new UnitGenerator.FileView(version(entry.getValue()), entry.getValue()))));
    }

    @Test
    void multipleEditsUseOriginalOffsetsAndEveryFileIsPreparedBeforeMutation() {
        String text = "alpha middle omega";
        var changes = patches.prepare(scope(Map.of("src/App.tsx", text)), patch(text,
                List.of(new RepairPatch.Edit("alpha", "much longer alpha"), new RepairPatch.Edit("omega", "end"))));
        assertEquals("much longer alpha middle end", changes.getFirst().content());
        assertEquals(version(text), changes.getFirst().expectedVersion());
    }

    @Test
    void staleAmbiguousMissingAndOverlappingSearchesRejectTheWholePatch() {
        String text = "abc abcdef";
        for (var edits : List.of(List.of(new RepairPatch.Edit("abc", "x")), List.of(new RepairPatch.Edit("missing", "x")),
                List.of(new RepairPatch.Edit("abcdef", "x"), new RepairPatch.Edit("def", "y")))) {
            assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope(Map.of("src/App.tsx", text)), patch(text, edits)));
        }
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope(Map.of("src/App.tsx", "changed")),
                patch(text, List.of(new RepairPatch.Edit("abcdef", "x")))));
    }

    @Test
    void diagnosticsForeignPathsDuplicateFilesAndEmptySearchCannotExpandAuthority() {
        String text = "before";
        var file = new RepairPatch.FilePatch("src/App.tsx", version(text), List.of(new RepairPatch.Edit("before", "after")));
        var scope = scope(Map.of("src/App.tsx", text));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope, new RepairPatch(1, "app", "sha256:" + "f".repeat(64), List.of(file))));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope, new RepairPatch(1, "app", revision, List.of(file, file))));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope,
                new RepairPatch(1, "app", revision, List.of(new RepairPatch.FilePatch("package.json", version(text), file.edits())))));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope, patch(text, List.of(new RepairPatch.Edit("", "after")))));
    }

    @Test
    void fencedJsonWorksButUnknownFieldsTwoObjectsAndTruncationAreRejected() {
        var patch = patch("before", List.of(new RepairPatch.Edit("before", "after")));
        String json = mapper.writeValueAsString(patch);
        assertEquals(patch, patches.decode("```json\n" + json + "\n```", "stop"));
        assertThrows(IllegalArgumentException.class, () -> patches.decode(json, "length"));
        assertThrows(IllegalArgumentException.class, () -> patches.decode(json + "\n" + json, "stop"));
        assertThrows(IllegalArgumentException.class, () -> patches.decode(json.replace("\"schemaVersion\":1", "\"extra\":true,\"schemaVersion\":1"), "stop"));
    }

    @Test
    void badSecondFileAndInvalidUtf16YieldNoPreparedWriteSet() {
        String text = "before";
        var good = patch(text, List.of(new RepairPatch.Edit("before", "after"))).patches().getFirst();
        var bad = new RepairPatch.FilePatch("src/Other.tsx", version(text), List.of(new RepairPatch.Edit("missing", "x")));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope(Map.of("src/App.tsx", text, "src/Other.tsx", text)),
                new RepairPatch(1, "app", revision, List.of(good, bad))));
        assertThrows(IllegalArgumentException.class, () -> patches.prepare(scope(Map.of("src/App.tsx", text)),
                patch(text, List.of(new RepairPatch.Edit("before", "\uD800")))));
    }
}
