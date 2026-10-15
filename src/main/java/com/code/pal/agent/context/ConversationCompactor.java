package com.code.pal.agent.context;

import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import tools.jackson.databind.node.ArrayNode;

/**
 * Keeps a run's conversation small as it grows. Every model call resends the whole conversation, so
 * old bulky parts are paid for again and again: tool results the model has already used (file reads,
 * listings, searches) and the file contents it wrote in earlier tool calls. Before a call, those older
 * than the last {@code keepTurns} model turns are replaced by short stubs; tool call ids (and any
 * provider data attached to calls) stay, so the conversation remains valid for every provider.
 */
public final class ConversationCompactor {

    /** Write tools whose arguments carry file contents. */
    private static final Set<String> CONTENT_ARGUMENTS = Set.of("content", "oldText", "newText");
    private static final Set<String> WRITE_TOOLS = Set.of("createFile", "createFiles", "patchFile");

    private final ObjectMapper objectMapper;
    private final int keepTurns;
    private final int minChars;

    /**
     * @param keepTurns the most recent model turns (with their tool results) left as they are
     * @param minChars  smaller parts are left as they are; a stub would save little
     */
    public ConversationCompactor(ObjectMapper objectMapper, int keepTurns, int minChars) {
        this.objectMapper = objectMapper;
        this.keepTurns = Math.max(1, keepTurns);
        this.minChars = Math.max(0, minChars);
    }

    /** Compacts {@code messages} in place; returns the characters saved (tool-result characters and argument characters). */
    public Saved compact(List<LlmMessage> messages) {
        int cutoff = cutoffIndex(messages);
        if (cutoff <= 0) return Saved.NONE;
        Map<String, ToolCall> calls = new HashMap<>();
        int resultChars = 0;
        int argumentChars = 0;
        Set<String> omittedReads=new LinkedHashSet<>();
        for (int i = 0; i < cutoff; i++) {
            LlmMessage message = messages.get(i);
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                message.toolCalls().forEach(call -> calls.put(call.id(), call));
                List<ToolCall> compacted = message.toolCalls().stream().map(this::compactCall).toList();
                int saved = argumentChars(message.toolCalls()) - argumentChars(compacted);
                if (saved > 0) {
                    messages.set(i, new LlmMessage(message.role(), message.content(), compacted, message.toolCallId(), message.images()));
                    argumentChars += saved;
                }
            } else if ("tool".equals(message.role()) && message.content() != null && message.content().length() >= minChars
                    && !message.content().startsWith(STUB_PREFIX)) {
                ToolCall call = calls.get(message.toolCallId());
                String stub = new ToolResultMemory(objectMapper).compact(message.content(),call,"Earlier tool body compacted");
                int saved=message.content().length()-stub.length();
                if(saved>0) {
                    resultChars+=saved;
                    messages.set(i,new LlmMessage("tool",stub,null,message.toolCallId(),message.images()));
                    if(call!=null && call.name().equals("readFile")){String path=argument(call,"path");if(path!=null)omittedReads.add(path);}
                }
            }
        }
        return new Saved(resultChars, argumentChars,Set.copyOf(omittedReads));
    }

    /** Characters a compaction removed: from tool results (counted in the run's context budget) and from call arguments. */
    public record Saved(int resultChars, int argumentChars,Set<String> omittedReadPaths) {
        public static final Saved NONE = new Saved(0, 0,Set.of());
        public Saved(int resultChars,int argumentChars){this(resultChars,argumentChars,Set.of());}

        public int total() { return resultChars + argumentChars; }
    }

    private static final String STUB_PREFIX = "{\"compacted\":true";

    /** Index of the oldest of the last {@code keepTurns} model turns that called tools; everything before it may be compacted. */
    private int cutoffIndex(List<LlmMessage> messages) {
        int seen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                if (++seen == keepTurns) {
                    // A call/result pair may cross this cutoff only in malformed/interleaved input.
                    Set<String> laterResults=new HashSet<>();
                    for(int later=i;later<messages.size();later++) if(messages.get(later).toolCallId()!=null)laterResults.add(messages.get(later).toolCallId());
                    for(int earlier=0;earlier<i;earlier++)if(messages.get(earlier).toolCalls()!=null)
                        for(var call:messages.get(earlier).toolCalls())if(laterResults.contains(call.id()))return earlier;
                    return i;
                }
            }
        }
        return -1;
    }

    /** A write call with its file contents replaced by their length; other calls unchanged. */
    private ToolCall compactCall(ToolCall call) {
        if (!WRITE_TOOLS.contains(call.name()) || call.arguments() == null || call.arguments().length() < minChars) return call;
        JsonNode args;
        try {
            args = objectMapper.readTree(call.arguments());
        } catch (RuntimeException unreadable) {
            return call;
        }
        if (!(args instanceof ObjectNode object)) return call;
        ObjectNode copy = object.deepCopy();
        boolean changed = omitContents(copy);
        if (copy.get("files") instanceof ArrayNode files) {
            for (JsonNode file : files) if (file instanceof ObjectNode entry) changed |= omitContents(entry);
        }
        if (!changed) return call;
        return new ToolCall(call.id(), call.name(), objectMapper.writeValueAsString(copy), call.extraContent());
    }

    private static boolean omitContents(ObjectNode node) {
        boolean changed = false;
        for (String field : CONTENT_ARGUMENTS) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (text.length() > 40 && !text.startsWith("[omitted")) {
                    node.put(field, "[omitted: " + text.length() + " characters; argument body, outcome is in the tool result]");
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String argument(ToolCall call, String field) {
        if (call.arguments() == null) return null;
        try {
            JsonNode value = objectMapper.readTree(call.arguments()).get(field);
            return value != null && value.isValueNode() && !value.isNull() ? value.asText() : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static int argumentChars(List<ToolCall> calls) {
        return calls.stream().mapToInt(call -> call.arguments() == null ? 0 : call.arguments().length()).sum();
    }
}
