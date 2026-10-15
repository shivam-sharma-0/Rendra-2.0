package com.code.pal.agent;
import com.code.pal.agent.domain.AgentRun;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuildReplyTextTest {
    @Test void featuresSurviveButInventedOutcomeAndFileInventoriesDoNot() {
        var run=AgentRun.builder().previewStatus("FAILED").build();
        var text=BuildReplyText.success("Added task editing and completion.\nThe app is live.\n\nChanged files:\n- src/App.tsx",run);
        assertTrue(text.contains("task editing"));assertFalse(text.contains("live"));assertFalse(text.contains("App.tsx"));
        assertTrue(text.contains("preview could not be prepared"));assertFalse(text.contains("preview is ready"));
    }
    @Test void previewReadinessComesFromPersistedState() {
        var run=AgentRun.builder().previewStatus("READY").build();assertTrue(BuildReplyText.success(null,run).contains("preview is ready"));
        run.setPreviewStatus(null);assertTrue(BuildReplyText.success("Built src/App.tsx",run).contains("preview is not ready yet"));
    }
}
