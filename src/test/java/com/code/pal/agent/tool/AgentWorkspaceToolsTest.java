package com.code.pal.agent.tool;

import com.code.pal.agent.tool.impl.ListFilesTool;
import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkspaceToolsTest {

    private final LocalProject project = LocalProject.create();

    @Test
    void rejectsPathTraversalBeforeAnyToolCanReadOrWrite() {
        AgentContext context = context();

        for (String path : new String[]{"../outside.txt", "C:\\outside.txt", "/etc/passwd", "src/../pom.xml",
                ".codepal/history/x", ".git/config", ".secrets/key", ""}) {
            assertThrows(IllegalArgumentException.class, () -> context.resolvePath(path), path);
        }
        assertEquals("src/App.tsx", context.resolvePath("./src/./App.tsx"));
        assertEquals(".", context.resolveDirectory("."));
    }

    @Test
    void readsAProjectFileWithAVersion() {
        project.write("src/App.tsx", "export default 42");

        @SuppressWarnings("unchecked") Map<String, Object> read =
                (Map<String, Object>) new ReadFileTool().execute(Map.of("path", "src/App.tsx"), context());

        assertEquals("export default 42", read.get("content"));
        assertTrue(read.get("version").toString().startsWith("sha256:"));
    }

    @Test
    void boundsReadMemory() {
        project.write("read-large.txt", "y".repeat(100_001));

        @SuppressWarnings("unchecked") Map<String, Object> readResult =
                (Map<String, Object>) new ReadFileTool().execute(Map.of("path", "read-large.txt"), context());

        assertEquals(true, readResult.get("truncated"));
        assertTrue(readResult.get("content").toString().length() < 100_100);
    }

    @Test
    void listingHidesInternalStateAndSecrets() {
        project.write("src/App.tsx", "x").write(".codepal/builds/b.json", "{}").write(".secrets/key", "k");

        String listing = new ListFilesTool().execute(Map.of("path", "."), context());

        assertTrue(listing.contains("src/"), listing);
        assertFalse(listing.contains(".codepal"), listing);
        assertFalse(listing.contains(".secrets"), listing);
        assertThrows(IllegalArgumentException.class, () -> new ListFilesTool().execute(Map.of("path", "src/App.tsx"), context()));
    }

    private AgentContext context() {
        return new AgentContext(project.projectId, 1L, ProjectType.BUILD, ".", project.files);
    }
}
