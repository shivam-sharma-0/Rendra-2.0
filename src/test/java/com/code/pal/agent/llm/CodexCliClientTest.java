package com.code.pal.agent.llm;

import com.code.pal.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodexCliClientTest {
    private final LlmClient.LlmRequest request = new LlmClient.LlmRequest("codex", "gpt-5.6-luna",
            List.of(LlmClient.LlmMessage.system("Implement the unit"), LlmClient.LlmMessage.user("{\"unit\":\"ui\"}")),
            List.of(), 0, Duration.ofSeconds(30));

    @Test void theDeveloperRouteIsOffByDefaultEvenThroughTheProviderClient() {
        var properties = new AgentProperties();
        assertFalse(properties.getDev().isCodexEnabled());
        assertEquals("low", properties.getDev().getCodexReasoningEffort());
        var refused = assertThrows(LlmException.class, () -> new OpenAiCompatibleLlmClient(properties, new ObjectMapper()).chat(request));
        assertTrue(refused.getMessage().contains("disabled"), refused.getMessage());
    }

    @Test void toolCallsAreRefusedAndTheRolesKeepTheirOrderInTheSinglePrompt() {
        var dev = new AgentProperties.Dev();
        dev.setCodexEnabled(true);
        var tools = new LlmClient.LlmRequest("codex", "gpt-5.6-luna", request.messages(),
                List.of(new LlmClient.ToolSpec("write", "Write a file", Map.of())), 0, Duration.ofSeconds(30));
        assertThrows(LlmException.class, () -> CodexCliClient.run(tools, dev));
        String prompt = CodexCliClient.prompt(request);
        assertTrue(prompt.startsWith("Answer as a JSON API"));
        assertTrue(prompt.indexOf("### system\nImplement the unit") < prompt.indexOf("### user\n{\"unit\":\"ui\"}"), prompt);
    }
}
