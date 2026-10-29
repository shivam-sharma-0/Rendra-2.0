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
        for (String title : List.of("Agent started", "Agent completed", "Model called",
                "Project context prepared", "Project context usage", "Some future status")) {
            hidden(AgentStepType.STATUS, title, "{\"iteration\":3}");
        }
    }

    @Test
    void structuredBuildStagesShowTheirClippedTitleWhileUnmarkedStatusesStayInternal() {
        // 2026-10-29: the contract pipeline's stages were stored but hidden, so the panel sat on "Waiting to start".
        AgentActivity stage = visible(AgentStepType.STATUS, "Writing: Render the home page\n with task list", "{\"progress\":true}");
        assertEquals(Kind.PROGRESS, stage.kind());
        assertEquals("Writing: Render the home page with task list", stage.title());
        assertNull(stage.ok());
        assertEquals(121, visible(AgentStepType.STATUS, "x".repeat(400), "{\"progress\":true}").title().length());
        hidden(AgentStepType.STATUS, "Writing: Render the home page", null);
        hidden(AgentStepType.STATUS, "Writing: Render the home page", "{\"progress\":false}");
        hidden(AgentStepType.STATUS, "  ", "{\"progress\":true}");
        assertEquals("Waiting to start", visible(AgentStepType.STATUS, "Queued", "{\"progress\":true}").title());
    }

    @Test
    void stagesCarryTheirPartsAndAFoundProblemIsAFailedRowWithItsDetail() {
        AgentActivity writing = visible(AgentStepType.STATUS, "Writing 2 parts",
                "{\"progress\":true,\"items\":[\"Render the login page\",\"Implement the\\n todos API\"]}");
        assertEquals(List.of("Render the login page", "Implement the todos API"), writing.items());
        assertNull(writing.ok());

        AgentActivity problem = visible(AgentStepType.STATUS, "Problem found: the “complete” step did not work in the browser",
                "{\"progress\":true,\"ok\":false,\"detail\":\"Required interaction ui_complete did not pass\"}");
        assertEquals(Kind.PROGRESS, problem.kind());
        assertEquals(Boolean.FALSE, problem.ok());
        assertEquals("Required interaction ui_complete did not pass", problem.detail());
    }

    @Test
    void aFailedRunNamesItsCauseWhenTheRunnerRecordedOne() {
        // 2026-10-29: a stalled repair after a failed browser check read only "Something went wrong".
        AgentActivity named = visible(AgentStepType.STATUS, "Agent failed",
                "{\"modelCalls\":4,\"message\":\"I couldn't finish this build: the app built, but it did not behave as approved.\"}");
        assertEquals(Kind.FAILED, named.kind());
        assertEquals("The build stopped", named.title());
        assertTrue(named.detail().startsWith("I couldn't finish this build"));
        assertEquals("Something went wrong", visible(AgentStepType.STATUS, "Agent failed", "{\"modelCalls\":4}").title());
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

        AgentActivity started = visible(AgentStepType.TOOL_RESULT, "startProject", "{\"success\":true,\"paths\":[\"package.json\"]}");
        assertEquals(Kind.CREATE, started.kind());
        assertEquals("Set up the project", started.title());
        assertEquals(List.of("package.json"), started.paths());

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
    void failedChangeAttemptsAreVisibleAndBookkeepingIsHidden() {
        var failed=visible(AgentStepType.TOOL_RESULT, "patchFile", "{\"success\":false,\"errorCode\":\"PATCH_TARGET_NOT_FOUND\",\"path\":\"a.ts\"}");
        assertEquals(Kind.STATUS,failed.kind());assertEquals(false,failed.ok());assertEquals(List.of("a.ts"),failed.paths());
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
        assertEquals(Kind.STATUS, visible(AgentStepType.THINKING, "Model reasoning", "{\"reasoning\":\"…\"}").kind());
    }

    @Test
    void publicDataDropsTelemetryButKeepsWhatTheUiUses() {
        String data = mapper.publicData("{\"reasoning\":\"why\",\"toolCallCount\":2,\"modelCalls\":5,\"provider\":\"mistral\",\"changedFiles\":[\"a\"]}");
        assertFalse(data.contains("\"reasoning\""));
        assertTrue(data.contains("\"changedFiles\""));
        assertFalse(data.contains("modelCalls"));
        assertFalse(data.contains("mistral"));
        assertFalse(data.contains("toolCallCount"));
        assertNull(mapper.publicData("{\"iterations\":3,\"modelCalls\":4}"));
        assertNull(mapper.publicData(null));
        assertNull(mapper.publicData("not json"));
    }
    @Test void allowlistRejectsNestedTelemetryAndWrongTypes() {
        String json=mapper.publicData("{\"reasoning\":{\"provider\":\"private\"},\"paths\":[\"src/App.tsx\",{\"model\":\"private\"}],\"message\":[\"private\"],\"status\":\"SAVING\",\"unknown\":{\"token\":\"private\"}}");
        assertEquals("{\"status\":\"SAVING\",\"paths\":[\"src/App.tsx\"]}",json);
        assertNull(mapper.publicData("[\"private\"]"));
        assertNull(mapper.publicData("{\"success\":\"true\",\"repairAttempts\":-1}"));
    }
    @Test void retrySaveAndRecoveryAreVisibleWithoutProviderMetadata() {
        for(String title:List.of("LLM provider unavailable","Saving changes","Save recovery pending"))assertEquals(Kind.STATUS,visible(AgentStepType.STATUS,title,"{\"provider\":\"private\"}").kind());
        assertNull(mapper.publicData("{\"provider\":\"private\",\"nested\":{\"model\":\"private\"}}"));
    }
}
