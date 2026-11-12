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

    @Test void theRolesKeepTheirOrderInTheSinglePrompt() {
        String prompt = CodexCliClient.prompt(request);
        assertTrue(prompt.startsWith("Answer as a JSON API"));
        assertTrue(prompt.indexOf("### system\nImplement the unit") < prompt.indexOf("### user\n{\"unit\":\"ui\"}"), prompt);
        assertFalse(prompt.contains("### tools"));
    }

    @Test void toolCallsGoThroughTheJsonProtocolInBothDirections() {
        var messages = List.of(LlmClient.LlmMessage.system("Edit the app"), LlmClient.LlmMessage.user("Add a title"),
                LlmClient.LlmMessage.assistantToolCalls(List.of(new LlmClient.ToolCall("codex-1", "readFile", "{\"path\":\"src/Home.tsx\"}"))),
                LlmClient.LlmMessage.toolResult("codex-1", "export default function Home() {}"));
        var tools = new LlmClient.LlmRequest("codex", "gpt-5.6-sol", messages,
                List.of(new LlmClient.ToolSpec("patchFile", "Patch a file", Map.of("type", "object"))), 0, Duration.ofSeconds(30));
        String prompt = CodexCliClient.prompt(tools);
        assertTrue(prompt.contains("\"tool_calls\":[{\"name\":\"<tool name>\""), prompt);
        assertTrue(prompt.contains("### tools\n{\"") && prompt.contains("\"name\":\"patchFile\""), prompt);
        assertTrue(prompt.contains("### assistant\n{\"tool_calls\":[{") && prompt.contains("\"name\":\"readFile\""), prompt);
        assertTrue(prompt.contains("### tool codex-1\nexport default function Home() {}"), prompt);

        var calls = CodexCliClient.parse("```json\n{\"tool_calls\":[{\"name\":\"patchFile\",\"arguments\":{\"path\":\"src/Home.tsx\",\"oldText\":\"a\",\"newText\":\"b\"}}]}\n```", true);
        assertTrue(calls.hasToolCalls());
        assertEquals("patchFile", calls.toolCalls().getFirst().name());
        assertEquals("codex-1", calls.toolCalls().getFirst().id());
        assertTrue(calls.toolCalls().getFirst().arguments().contains("\"oldText\":\"a\""));
        assertNull(calls.content());

        var reply = CodexCliClient.parse("{\"final\":\"Added the title.\"}", true);
        assertFalse(reply.hasToolCalls());
        assertEquals("Added the title.", reply.content());
        assertEquals("plain text answer", CodexCliClient.parse("plain text answer", true).content(), "a non-protocol reply is the reply");
        assertEquals("{\"files\":[]}", CodexCliClient.parse("{\"files\":[]}", false).content(), "tool-free calls keep the raw JSON");
    }
}
