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

    @Test void failedWriteRetainsFailureAndDoesNotClaimArgumentsWereWritten() {
        var messages=conversation();
        messages.set(5,LlmMessage.toolResult("c2",json.writeValueAsString(Map.of("success",false,"error",Map.of("code","WRITE_FAILED",
                "message","Disk write failed","details",Map.of("path","src/Game.tsx","actualVersion","absent","action","Retry after verification","diagnostics",List.of(Map.of("message","x".repeat(3000)))))))));
        compactor.compact(messages);
        var result=json.readTree(messages.get(5).content());
        assertFalse(result.path("success").asBoolean());assertEquals("WRITE_FAILED",result.path("error").path("code").asString());
        assertEquals("absent",result.path("error").path("details").path("actualVersion").asString());
        assertFalse(messages.get(4).toolCalls().getFirst().arguments().contains("already written"));
        assertEquals(Map.of("signature","s-c2"),messages.get(4).toolCalls().getFirst().extraContent());
    }
    @Test void executionSuccessDoesNotHideValidationFailureOrUnresolvedDiagnostics() {
        String original=json.writeValueAsString(Map.of("success",true,"output",Map.of("changeValidationPassed",false,"repairAttemptsRemaining",2,
                "changeSetRevision",7,"introducedDiagnostics",List.of(Map.of("file","src/App.tsx","code","TS2307","message","missing dependency")),"stdout","x".repeat(4000))));
        String compact=new ToolResultMemory(json).compact(original,null,"Test");var result=json.readTree(compact);
        assertTrue(result.path("success").asBoolean());assertFalse(result.path("output").path("changeValidationPassed").asBoolean());
        assertEquals(7,result.path("output").path("changeSetRevision").asInt());
        assertEquals("TS2307",result.path("output").path("introducedDiagnostics").get(0).path("code").asString());
        assertFalse(compact.contains("x".repeat(100)));assertTrue(compact.length()<original.length());
        assertEquals(compact,new ToolResultMemory(json).compact(compact,null,"Again"));
    }
    @Test void unknownBodiesStayUnknownAndMetadataCannotGrowWithoutBound() {
        var reducer=new ToolResultMemory(json);
        assertEquals("UNKNOWN",json.readTree(reducer.compact("not JSON "+"x".repeat(3000),null,"Test")).path("outcome").asString());
        List<Map<String,Object>> diagnostics=new ArrayList<>();for(int i=0;i<1000;i++)diagnostics.add(Map.of("file","a".repeat(500),"message","m".repeat(2000)));
        String result=reducer.compact(json.writeValueAsString(Map.of("success",true,"output",Map.of("diagnostics",diagnostics,"changeValidationPassed",false))),null,"Test");
        assertTrue(result.length()<12000,result.length()+" chars");assertTrue(json.readTree(result).path("metadataPartial").asBoolean());
        assertFalse(json.readTree(result).path("output").path("changeValidationPassed").asBoolean());
    }
    @Test void compactedReadsCanBeReFetchedInsteadOfDeduplicatedAway() {
        var trace=new ProjectContextTrace();trace.recordRead("src/App.tsx",100,"v1",100);
        var saved=compactor.compact(conversation());assertTrue(saved.omittedReadPaths().contains("src/App.tsx"));
        saved.omittedReadPaths().forEach(trace::forgetRead);
        assertFalse(trace.wasReadUnchanged("src/App.tsx",100,"v1"));
    }
    @Test void unresolvedPlanStepsAndStableSourceVersionsRemainAvailable() {
        String result=new ToolResultMemory(json).compact(json.writeValueAsString(Map.of("success",true,"output",Map.of("revision",3,
                "steps",List.of(Map.of("id",1,"description","Repair API","status","IN_PROGRESS","note","Still missing auth")),
                "actualVersion","sha256:abc","content","x".repeat(3000)))),null,"Test");
        var output=json.readTree(result).path("output");assertEquals("IN_PROGRESS",output.path("steps").get(0).path("status").asString());
        assertEquals("sha256:abc",output.path("actualVersion").asString());assertEquals(3,output.path("revision").asInt());
    }
}
