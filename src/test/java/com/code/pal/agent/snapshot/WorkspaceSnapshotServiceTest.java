package com.code.pal.agent.snapshot;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceSnapshotServiceTest {

    @Test
    void restoresTheExactPreRunWorkspaceAndRemovesAgentCreatedFiles() throws Exception {
        Path workspace = Files.createTempDirectory(Path.of("target", "test-workspaces"), "snapshot-");
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.txt"), "before");

        WorkspaceSnapshotService snapshots = new WorkspaceSnapshotService();
        String reference = snapshots.createSnapshot(workspace, UUID.randomUUID());

        Files.writeString(workspace.resolve("src/App.txt"), "after");
        Files.writeString(workspace.resolve("generated.txt"), "remove me");
        snapshots.restore(workspace, reference);

        assertEquals("before", Files.readString(workspace.resolve("src/App.txt")));
        assertFalse(Files.exists(workspace.resolve("generated.txt")));
    }
}
