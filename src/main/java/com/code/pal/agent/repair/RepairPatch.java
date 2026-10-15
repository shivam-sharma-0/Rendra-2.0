package com.code.pal.agent.repair;

import java.util.List;

/** Data-only repair response. All authority comes from the backend scope passed to RepairPatches. */
public record RepairPatch(int schemaVersion, String batchId, String diagnosticsRevision, List<FilePatch> patches) {
    public record FilePatch(String path, String expectedVersion, List<Edit> edits) {
        public FilePatch {
            edits = List.copyOf(edits);
        }
    }

    public record Edit(String search, String replace) {
    }

    public RepairPatch {
        patches = List.copyOf(patches);
    }
}
