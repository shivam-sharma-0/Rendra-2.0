package com.code.pal.agent.context;

import com.code.pal.agent.llm.LlmClient.ToolCall;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Deterministic bounded result memory: omit bodies, never invent an execution/validation outcome. */
public final class ToolResultMemory {
    private static final Set<String> FIELDS=Set.of("path","sourcePath","targetPath","version","beforeVersion","afterVersion",
            "expectedVersion","actualVersion","revision","changeSetRevision","repairAttempts","repairAttemptsRemaining","repairLimitExhausted",
            "operation","status","state","available","commandSucceeded","changeValidationPassed","valid","passed","started","timedOut","exitCode",
            "size","sizeBytes","directory","projectionId","index","offset","nextOffset","totalChars","partial","hasMore","truncated",
            "createdFiles","changedPaths","paths","touchedPaths","issues","diagnostics","introducedDiagnostics","preExistingDiagnostics",
            "code","message","action","file","line","column","severity","source","details","unitId","contractRevision",
            "steps","title","notes","remaining","field","id","description","note","reason","updatedAt","completed","checks","outcome","required");
    private static final List<String> CORE=List.of("code","outcome","status","state","available","commandSucceeded","changeValidationPassed","valid","passed","started",
            "timedOut","exitCode","revision","changeSetRevision","repairAttempts","repairAttemptsRemaining","repairLimitExhausted","version","beforeVersion",
            "afterVersion","expectedVersion","actualVersion","projectionId","index","offset","nextOffset","totalChars","partial","hasMore","path","sourcePath","targetPath","message","action");
    private final ObjectMapper mapper;
    public ToolResultMemory(ObjectMapper mapper){this.mapper=mapper;}
    public String compact(String original,ToolCall call,String reason) {
        ObjectNode stub=mapper.createObjectNode();stub.put("compacted",true);stub.put("bodyOmitted",true);
        if(call!=null){stub.put("tool",call.name());try{var args=mapper.readTree(call.arguments());if(args.path("path").isString())stub.put("path",clip(args.path("path").asString(),512));}catch(RuntimeException ignored){}}
        boolean[] partial={false};
        int[] budget={96,3000}; // Shared node/text allowance, not multiplied by each diagnostic array.
        try {
            var root=mapper.readTree(original);
            if(original.length()<=12_000 && root.path("compacted").asBoolean(false)
                    && root.path("bodyOmitted").asBoolean(false) && root.path("sourceHash").isString()) return original;
            if(root.path("success").isBoolean())stub.set("success",root.path("success"));else stub.put("outcome","UNKNOWN");
            if(root.path("error").isObject())stub.set("error",metadata(root.path("error"),0,partial,budget));
            if(root.path("output").isObject() || root.path("output").isArray())stub.set("output",metadata(root.path("output"),0,partial,budget));
        } catch(RuntimeException unreadable){stub.put("outcome","UNKNOWN");}
        stub.put("metadataPartial",partial[0]);stub.put("sourceChars",original.length());stub.put("sourceHash",hash(original));
        stub.put("note",reason+"; body omitted. Execution and validation facts above are distinct. Re-read omitted bodies; do not infer success from omission.");
        return mapper.writeValueAsString(stub);
    }
    private JsonNode metadata(JsonNode node,int depth,boolean[] partial,int[] budget) {
        if(depth>4 || budget[0]--<=0){partial[0]=true;return mapper.getNodeFactory().textNode("[metadata omitted]");}
        if(node.isObject()) {
            ObjectNode copy=mapper.createObjectNode();
            for(String key:CORE)if(node.has(key)) {
                if(budget[0]<=0){partial[0]=true;break;}
                copy.set(key,metadata(node.get(key),depth+1,partial,budget));
            }
            for(var entry:node.properties())if(FIELDS.contains(entry.getKey()) && !CORE.contains(entry.getKey())) {
                if(budget[0]<=0){partial[0]=true;break;}
                copy.set(entry.getKey(),metadata(entry.getValue(),depth+1,partial,budget));
            }
            return copy;
        }
        if(node.isArray()) {
            ArrayNode copy=mapper.createArrayNode();int index=0;
            for(var item:node){if(index++>=32 || budget[0]<=0){partial[0]=true;break;}copy.add(metadata(item,depth+1,partial,budget));}
            return copy;
        }
        if(node.isString()) {
            String text=node.asString();int limit=Math.min(512,Math.max(0,budget[1]));
            if(text.length()>limit)partial[0]=true;String clipped=clip(text,limit);budget[1]-=clipped.length();
            return mapper.getNodeFactory().textNode(clipped);
        }
        return node.deepCopy();
    }
    private static String clip(String text,int chars){return text.length()<=chars?text:text.substring(0,chars);}
    private static String hash(String text){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
