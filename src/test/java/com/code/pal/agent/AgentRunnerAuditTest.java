package com.code.pal.agent;

import com.code.pal.agent.tool.ToolAudit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The timeline records which files a tool touched and a plan's step titles — never file contents. */
class AgentRunnerAuditTest {

    @Test
    void keepsPathsAndDropsContents() {
        Map<String, Object> audit = ToolAudit.paths(Map.of("path", "src/App.tsx", "content", "secret code",
                "oldText", "a", "newText", "b", "expectedVersion", "v1"));
        assertEquals(Map.of("path", "src/App.tsx"), audit);
    }

    @Test
    void collectsCreateFilesPathsAndRenameTargets() {
        Map<String, Object> created = ToolAudit.paths(Map.of("files", List.of(
                Map.of("path", "a.ts", "content", "x"), Map.of("path", "b.ts", "content", "y"), "not a file")));
        assertEquals(List.of("a.ts", "b.ts"), created.get("paths"));
        assertFalse(created.toString().contains("content"));

        Map<String, Object> renamed = ToolAudit.paths(Map.of("sourcePath", "a.ts", "targetPath", "b.ts", "expectedVersion", "v"));
        assertEquals(Map.of("sourcePath", "a.ts", "targetPath", "b.ts"), renamed);
    }

    @Test
    void keepsPlanStepTitlesClipped() {
        String longStep = "x".repeat(400);
        Map<String, Object> plan = ToolAudit.paths(Map.of("steps", List.of("Layout", longStep)));
        List<?> steps = (List<?>) plan.get("steps");
        assertEquals("Layout", steps.get(0));
        assertTrue(steps.get(1).toString().length() <= 303);
    }

    @Test
    void aToolThatChoosesItsOwnFilesNamesThemInItsResult() {
        Map<String, Object> audit = ToolAudit.result(com.code.pal.agent.tool.ToolExecutionResult.success(
                Map.of("stack", "vite-react", "createdFiles", List.of("package.json", "src/main.tsx"))), Map.of("stack", "vite-react"));
        assertEquals(List.of("package.json", "src/main.tsx"), audit.get("paths"));
        assertEquals(true, audit.get("success"));
    }

    @Test
    void ignoresNonStringPaths() {
        assertTrue(ToolAudit.paths(Map.of("path", 42, "files", "nope")).isEmpty());
    }
}
