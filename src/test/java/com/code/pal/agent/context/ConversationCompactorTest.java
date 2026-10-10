package com.code.pal.agent.context;

import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationCompactorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final ConversationCompactor compactor = new ConversationCompactor(json, 2, 800);

    private static LlmMessage call(String id, String tool, String arguments) {
        return new LlmMessage("assistant", null, List.of(new ToolCall(id, tool, arguments, Map.of("signature", "s-" + id))), null);
    }

    private List<LlmMessage> conversation() {
        String bigFile = "x".repeat(3_000);
        return new ArrayList<>(List.of(
                LlmMessage.system("system prompt"),
                LlmMessage.user("Build a game"),
                call("c1", "readFile", "{\"path\":\"src/App.tsx\"}"),
                LlmMessage.toolResult("c1", "{\"success\":true,\"output\":\"" + "y".repeat(5_000) + "\"}"),
                call("c2", "createFile", "{\"path\":\"src/Game.tsx\",\"content\":\"" + bigFile + "\"}"),
                LlmMessage.toolResult("c2", "{\"success\":true}"),
                call("c3", "listFiles", "{}"),
                LlmMessage.toolResult("c3", "{\"success\":true,\"output\":\"" + "z".repeat(2_000) + "\"}"),
                call("c4", "patchFile", "{\"path\":\"src/App.tsx\",\"expectedVersion\":\"v1\",\"oldText\":\"" + bigFile + "\",\"newText\":\"" + bigFile + "\"}"),
                LlmMessage.toolResult("c4", "{\"success\":true}")));
    }

    @Test
    void compactsOlderTurnsAndKeepsTheLastTwo() {
        List<LlmMessage> messages = conversation();
        List<LlmMessage> lastTwoTurns = List.copyOf(messages.subList(6, 10));

        ConversationCompactor.Saved saved = compactor.compact(messages);

        assertEquals(lastTwoTurns, messages.subList(6, 10), "the last two model turns are left as they are");
        String readResult = messages.get(3).content();
        assertTrue(readResult.startsWith("{\"compacted\":true") && readResult.contains("\"tool\":\"readFile\"") && readResult.contains("src/App.tsx"), readResult);
        assertEquals("c1", messages.get(3).toolCallId(), "the result still answers its call");

        ToolCall written = messages.get(4).toolCalls().getFirst();
        assertEquals("c2", written.id());
        assertEquals(Map.of("signature", "s-c2"), written.extraContent(), "provider data on the call is kept");
        assertTrue(written.arguments().contains("\"path\":\"src/Game.tsx\"") && written.arguments().contains("[omitted: 3000 characters"), written.arguments());
        json.readTree(written.arguments());   // still valid JSON

        assertEquals("{\"success\":true}", messages.get(5).content(), "small results are left alone");
        assertTrue(saved.resultChars() > 4_500);
        assertTrue(saved.argumentChars() > 2_900);
    }

    @Test
    void compactingTwiceChangesNothingMore() {
        List<LlmMessage> messages = conversation();
        compactor.compact(messages);
        List<LlmMessage> once = List.copyOf(messages);
        assertEquals(0, compactor.compact(messages).total());
        assertEquals(once, messages);
    }

    @Test
    void aShortConversationIsLeftAlone() {
        List<LlmMessage> messages = new ArrayList<>(conversation().subList(0, 6));
        List<LlmMessage> before = List.copyOf(messages);
        assertEquals(0, compactor.compact(messages).total());
        assertEquals(before, messages);
    }

    @Test
    void unreadableArgumentsAreLeftAlone() {
        List<LlmMessage> messages = conversation();
        LlmMessage broken = call("c2", "createFile", "not json " + "q".repeat(2_000));
        messages.set(4, broken);
        compactor.compact(messages);
        assertSame(broken.toolCalls().getFirst(), messages.get(4).toolCalls().getFirst());
        assertFalse(messages.get(3).content().contains("y".repeat(100)), "other parts are still compacted");
    }
}
