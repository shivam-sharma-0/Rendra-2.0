package com.code.pal.agent.tool;

import com.code.pal.agent.tool.impl.ReadFileTool;
import com.code.pal.agent.tool.impl.WriteFileTool;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentWorkspaceToolsTest {

    Path workspace;

    @BeforeEach
    void createWorkspace() throws Exception {
        Path base = Path.of("target", "test-workspaces");
        Files.createDirectories(base);
        workspace = Files.createTempDirectory(base, "agent-");
    }

    @Test
    void rejectsPathTraversalBeforeAnyToolCanReadOrWrite() {
        AgentContext context = context();

        assertThrows(IllegalArgumentException.class, () -> context.resolveSafe("../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> context.resolveSafe("C:\\outside.txt"));
    }

    @Test
    void writesAndReadsAProjectFileWithinTheWorkspace() throws Exception {
        AgentContext context = context();
        WriteFileTool writer = new WriteFileTool();
        ReadFileTool reader = new ReadFileTool();

        String writeResult = writer.execute(Map.of("path", "src/App.tsx", "content", "export default 42"), context);

        assertEquals("export default 42", Files.readString(workspace.resolve("src/App.tsx")));
        assertTrue(writeResult.startsWith("Wrote "));
        @SuppressWarnings("unchecked") Map<String, Object> read = (Map<String, Object>) reader.execute(Map.of("path", "src/App.tsx"), context);
        assertEquals("export default 42", read.get("content"));
        assertTrue(read.get("version").toString().startsWith("sha256:"));
    }

    @Test
    void rejectsOversizedWritesAndBoundsReadMemory() throws Exception {
        AgentContext context = context();
        WriteFileTool writer = new WriteFileTool();
        ReadFileTool reader = new ReadFileTool();

        String oversized = "x".repeat(1_000_001);
        assertTrue(writer.execute(Map.of("path", "large.txt", "content", oversized), context).startsWith("Error:"));

        Files.writeString(workspace.resolve("read-large.txt"), "y".repeat(100_001));
        @SuppressWarnings("unchecked") Map<String, Object> readResult = (Map<String, Object>) reader.execute(Map.of("path", "read-large.txt"), context);
        assertEquals(true, readResult.get("truncated"));
        assertTrue(readResult.get("content").toString().length() < 100_100);
    }

    private AgentContext context() {
        return new AgentContext(UUID.randomUUID(), 1L, ProjectType.BUILD, workspace);
    }
}
