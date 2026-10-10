package com.code.pal.agent.activity;

import com.code.pal.agent.activity.AgentActivity.Kind;
import com.code.pal.agent.domain.AgentStepType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActivityMapperTest {

    private final AgentActivityMapper mapper = new AgentActivityMapper(new ObjectMapper());

    private AgentActivity visible(AgentStepType type, String content, String data) {
        return mapper.map(type, content, data).orElseThrow(() -> new AssertionError(type + " " + content + " should be visible"));
    }

    private void hidden(AgentStepType type, String content, String data) {
        assertTrue(mapper.map(type, content, data).isEmpty(), type + " " + content + " should be internal");
    }

    @Test
    void internalStatusesAreHidden() {
        for (String title : List.of("Agent started", "Agent completed", "Model called", "LLM provider unavailable",
                "Project context prepared", "Project context usage", "Some future status")) {
            hidden(AgentStepType.STATUS, title, "{\"iteration\":3}");
        }
    }

    @Test
    void fileToolsShowOnceWithTheirPaths() {
        hidden(AgentStepType.TOOL_CALL, "createFile", "{\"argumentNames\":[\"content\",\"path\"],\"path\":\"src/App.tsx\"}");
        AgentActivity created = visible(AgentStepType.TOOL_RESULT, "createFile", "{\"success\":true,\"path\":\"src/App.tsx\"}");
        assertEquals(Kind.CREATE, created.kind());
        assertEquals(List.of("src/App.tsx"), created.paths());
        assertEquals(Boolean.TRUE, created.ok());

        AgentActivity many = visible(AgentStepType.TOOL_RESULT, "createFiles", "{\"success\":true,\"paths\":[\"a.ts\",\"b.ts\"]}");
        assertEquals(List.of("a.ts", "b.ts"), many.paths());

        AgentActivity moved = visible(AgentStepType.TOOL_RESULT, "renameFile", "{\"success\":true,\"sourcePath\":\"a.ts\",\"targetPath\":\"b.ts\"}");
        assertEquals(Kind.MOVE, moved.kind());
        assertEquals(List.of("a.ts", "b.ts"), moved.paths());
    }

    @Test
    void oldStepsWithoutPathsStillRead() {
        AgentActivity edited = visible(AgentStepType.TOOL_RESULT, "patchFile", "{\"success\":true}");
        assertEquals(Kind.EDIT, edited.kind());
        assertTrue(edited.paths().isEmpty());
    }

    @Test
    void failedFileAndPlanToolsAndBookkeepingAreHidden() {
        hidden(AgentStepType.TOOL_RESULT, "patchFile", "{\"success\":false,\"errorCode\":\"PATCH_TARGET_NOT_FOUND\",\"path\":\"a.ts\"}");
        hidden(AgentStepType.TOOL_RESULT, "updatePlanStep", "{\"success\":true}");
        hidden(AgentStepType.TOOL_RESULT, "someNewTool", "{\"success\":true}");
    }

    @Test
    void checksShowWhileRunningAndWithTheirOutcome() {
        AgentActivity running = visible(AgentStepType.TOOL_CALL, "runTypeCheck", "{\"argumentNames\":[]}");
        assertEquals(Kind.CHECK, running.kind());
        assertNull(running.ok());

        AgentActivity failed = visible(AgentStepType.TOOL_RESULT, "runTypeCheck",
                "{\"success\":true,\"validationPassed\":false,\"introducedDiagnosticCount\":2}");
        assertEquals(Boolean.FALSE, failed.ok());
        assertEquals("2 problems", failed.detail());

        AgentActivity finalCheck = visible(AgentStepType.STATUS, "Final validation", "{\"validationPassed\":true,\"introducedDiagnosticCount\":0}");
        assertEquals(Boolean.TRUE, finalCheck.ok());
        assertNull(finalCheck.detail());
    }

    @Test
    void planShowsItsSteps() {
        AgentActivity plan = visible(AgentStepType.TOOL_RESULT, "setPlan", "{\"success\":true,\"steps\":[\"Layout\",\"Game logic\"]}");
        assertEquals(Kind.PLAN, plan.kind());
        assertEquals("Made a plan with 2 steps", plan.title());
        assertEquals(List.of("Layout", "Game logic"), plan.items());
    }

    @Test
    void commitAndOutcomesAreVisible() {
        AgentActivity saved = visible(AgentStepType.STATUS, "Change set committed", "{\"changedFiles\":[\"a.ts\",\"b.ts\"],\"snapshotRef\":\"x\"}");
        assertEquals(Kind.SAVED, saved.kind());
        assertEquals("Saved 2 files", saved.title());
        assertEquals(List.of("a.ts", "b.ts"), saved.paths());

        assertEquals(Kind.STOPPED, visible(AgentStepType.STATUS, "Agent cancelled", "{\"modelCalls\":4}").kind());
        AgentActivity quota = visible(AgentStepType.STATUS, "Agent stopped: daily quota", "{\"message\":\"You have used today's AI quota.\"}");
        assertEquals(Kind.FAILED, quota.kind());
        assertEquals("You have used today's AI quota.", quota.detail());
        assertEquals(Kind.STATUS, visible(AgentStepType.STATUS, "Queued", "{\"status\":\"QUEUED\"}").kind());
        assertEquals(Kind.THINKING, visible(AgentStepType.THINKING, "Model reasoning", "{\"reasoning\":\"…\"}").kind());
    }

    @Test
    void publicDataDropsTelemetryButKeepsWhatTheUiUses() {
        String data = mapper.publicData("{\"reasoning\":\"why\",\"toolCallCount\":2,\"modelCalls\":5,\"provider\":\"mistral\",\"changedFiles\":[\"a\"]}");
        assertTrue(data.contains("\"reasoning\""));
        assertTrue(data.contains("\"changedFiles\""));
        assertFalse(data.contains("modelCalls"));
        assertFalse(data.contains("mistral"));
        assertFalse(data.contains("toolCallCount"));
        assertNull(mapper.publicData("{\"iterations\":3,\"modelCalls\":4}"));
        assertNull(mapper.publicData(null));
        assertNull(mapper.publicData("not json"));
    }
}
