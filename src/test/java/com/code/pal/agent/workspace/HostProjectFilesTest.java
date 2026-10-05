package com.code.pal.agent.workspace;

import com.code.pal.agent.change.FileVersions;
import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HostProjectFilesTest {

    private final Path base;
    private final HostProjectFiles files;
    private final UUID projectId = UUID.randomUUID();
    private final Path project;

    HostProjectFilesTest() throws Exception {
        base = Files.createTempDirectory(Path.of("target"), "host-files-");
        AgentProperties properties = new AgentProperties();
        properties.setWorkspaceBasePath(base.toString());
        files = new HostProjectFiles(new WorkspaceStorage(properties));
        project = base.resolve(projectId.toString()).toAbsolutePath();
        Files.createDirectories(project);
    }

    @Test
    void writesAtomicallyCreatingFoldersAndReadsBack() throws Exception {
        files.writeText(projectId, "src/app/page.tsx", "export default 1;\n");

        assertEquals("export default 1;\n", files.readText(projectId, "src/app/page.tsx"));
        try (var leftovers = Files.list(project.resolve("src/app"))) {
            assertEquals(List.of("page.tsx"), leftovers.map(path -> path.getFileName().toString()).toList(),
                    "no temp file may be left behind");
        }
    }

    @Test
    void relativeRootsSelectASubtree() {
        files.writeText(projectId, ".codepal/changesets/run1/workspace", "index.html", "<h1>staged</h1>");

        assertEquals("<h1>staged</h1>", files.readText(projectId, ".codepal/changesets/run1/workspace", "index.html"));
        assertFalse(files.exists(projectId, ".", "index.html"));
    }

    @Test
    void versionMatchesTheSandboxFingerprintFormat() throws Exception {
        files.writeText(projectId, "a.txt", "hello");
        Files.createDirectories(project.resolve("folder"));

        assertEquals(FileVersions.of(project.resolve("a.txt")), files.version(projectId, ".", "a.txt"));
        assertTrue(files.version(projectId, ".", "a.txt").startsWith("sha256:"));
        assertEquals("absent", files.version(projectId, ".", "missing.txt"));
        assertEquals("non-file", files.version(projectId, ".", "folder"));
    }

    @Test
    void rejectsTraversalAbsoluteAndProtectedPaths() {
        for (String bad : List.of("../outside.txt", "a/../../outside.txt", "/etc/passwd", "\\windows\\x", ".git/config",
                "src/.secrets/key", ".ssh/id_rsa", "bad\0name", "")) {
            assertThrows(IllegalArgumentException.class, () -> files.writeText(projectId, bad, "x"), bad);
            assertThrows(IllegalArgumentException.class, () -> files.exists(projectId, ".", bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> files.readText(projectId, "C:/Windows/win.ini"));
    }

    @Test
    void listsFilesAndFoldersRelativeToTheRootWithinTheDepth() {
        files.writeText(projectId, "index.html", "<html>");
        files.writeText(projectId, "src/main.ts", "main");
        files.writeText(projectId, "src/deep/nested/x.ts", "x");

        List<ProjectFiles.Entry> shallow = files.list(projectId, ".", 2);

        assertTrue(shallow.contains(new ProjectFiles.Entry("index.html", false, 6)));
        assertTrue(shallow.contains(new ProjectFiles.Entry("src", true, 0)));
        assertTrue(shallow.contains(new ProjectFiles.Entry("src/main.ts", false, 4)));
        assertTrue(shallow.contains(new ProjectFiles.Entry("src/deep", true, 0)));
        assertTrue(shallow.stream().noneMatch(entry -> entry.path().startsWith("src/deep/nested")), shallow.toString());
        assertEquals(List.of(), files.list(projectId, "does-not-exist", 3));
    }

    @Test
    void skippedFoldersAreNeitherListedNorEntered() {
        files.writeText(projectId, "src/main.ts", "main");
        files.writeText(projectId, "node_modules/pkg/index.js", "dep");
        files.writeText(projectId, "src/node_modules/inner.js", "dep");

        List<String> listed = files.list(projectId, ".", 5, java.util.Set.of("node_modules")).stream()
                .map(ProjectFiles.Entry::path).toList();

        assertTrue(listed.contains("src/main.ts"), listed.toString());
        assertTrue(listed.stream().noneMatch(path -> path.contains("node_modules")), listed.toString());
    }

    @Test
    void readHeadReturnsAtMostTheRequestedBytes() {
        files.writeText(projectId, "big.txt", "abcdef");

        assertEquals("abc", new String(files.readHead(projectId, ".", "big.txt", 3)));
        assertEquals("abcdef", new String(files.readHead(projectId, ".", "big.txt", 100)));
        assertThrows(IllegalStateException.class, () -> files.readBytes(projectId, ".", "big.txt", 3));
    }

    @Test
    void movesCopiesAndDeletesSingleFiles() {
        files.writeText(projectId, "a.txt", "content");

        files.copyFile(projectId, ".", "a.txt", ".codepal/history/r1/original", "a.txt");
        files.move(projectId, ".", "a.txt", "renamed/b.txt");
        files.delete(projectId, "missing.txt");            // absent is fine, like rm -f

        assertEquals("content", files.readText(projectId, ".codepal/history/r1/original", "a.txt"));
        assertEquals("content", files.readText(projectId, "renamed/b.txt"));
        assertFalse(files.exists(projectId, ".", "a.txt"));
        files.delete(projectId, "renamed/b.txt");
        assertFalse(files.regularFile(projectId, ".", "renamed/b.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.delete(projectId, "renamed"),
                "directories are removed in the sandbox, in one call");
    }

    @Test
    void enforcesReadLimitsAndRejectsNonFiles() {
        files.writeBytes(projectId, "uploads/logo.bin", new byte[] {0, 1, 2, 3, 4});

        assertArrayEquals(new byte[] {0, 1, 2, 3, 4}, files.readBytes(projectId, ".", "uploads/logo.bin", 5));
        assertThrows(IllegalStateException.class, () -> files.readBytes(projectId, ".", "uploads/logo.bin", 4));
        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "uploads"));
        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "missing.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.writeBytes(projectId, "big.bin", new byte[10_000_001]));
    }

    @Test
    void neverFollowsALinkPlantedInTheProject() throws Exception {
        Path outside = Files.createTempFile(base, "outside-", ".txt");
        Files.writeString(outside, "host secret");
        Path outsideDir = Files.createTempDirectory(base, "outside-dir-");
        try {
            Files.createSymbolicLink(project.resolve("evil-link"), outside);
            Files.createSymbolicLink(project.resolve("evil-dir"), outsideDir);
        } catch (Exception notPermitted) {
            assumeTrue(false, "this OS account cannot create symbolic links; covered by HostProjectFilesWslIT");
        }

        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "evil-link"));
        assertFalse(files.regularFile(projectId, ".", "evil-link"));
        assertEquals("non-file", files.version(projectId, ".", "evil-link"));
        assertThrows(IllegalStateException.class, () -> files.writeText(projectId, "evil-dir/planted.txt", "x"));
        assertThrows(IllegalStateException.class, () -> files.readText(projectId, "evil-dir/anything"));
        assertTrue(files.list(projectId, ".", 3).stream().noneMatch(entry -> entry.path().startsWith("evil")));
        try (var outsideFiles = Files.list(outsideDir)) {
            assertEquals(0, outsideFiles.count(), "nothing may be written through the linked folder");
        }
        assertEquals("host secret", Files.readString(outside));
    }
}
