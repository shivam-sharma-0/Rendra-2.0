package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component @RequiredArgsConstructor
public class PatchFileTool implements AgentTool {
    private final ChangeSetService changeSets;
    @Override public String name(){return "patchFile";}
    @Override public String description(){return "Replace one exact, unique text block in an existing file. Requires the version returned by readFile and fails on stale or ambiguous content.";}
    @Override public boolean mutating(){return true;}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("path",Map.of("type","string"),"expectedVersion",Map.of("type","string"),"oldText",Map.of("type","string"),"newText",Map.of("type","string")),"required",List.of("path","expectedVersion","oldText","newText"));}
    @Override public Object execute(Map<String,Object> args,AgentContext context){
        String path = java.nio.file.Path.of(context.resolvePath(CreateFileTool.text(args,"path"))).normalize().toString().replace('\\','/');
        if (context.contextTrace().patchLimitReached()) throw new com.code.pal.agent.tool.AgentToolException("PATCH_STAGNATION", "Repeated patch misses exhausted the configured limit");
        try {
            if (context.contextTrace().patchNeedsRead(path)) throw new com.code.pal.agent.tool.AgentToolException("PATCH_REREAD_REQUIRED", "Read this file again before retrying the patch",
                    Map.of("path", path, "actualVersion", context.files().version(context.projectId(), context.root(), path), "action", "Call readFile, then use its version and a unique target"));
            Object replacement=args.get("newText");
            if (!(replacement instanceof String next)) throw new IllegalArgumentException("newText must be a string; use empty text to delete a block");
            String expected=args.get("expectedVersion") instanceof String value ? value : null;
            Object result=changeSets.patch(context,path,expected,CreateFileTool.text(args,"oldText"),next);
            context.contextTrace().patchSucceeded(path);
            return result;
        } catch (com.code.pal.agent.tool.AgentToolException error) {
            if (java.util.Set.of("PATCH_TARGET_NOT_FOUND", "PATCH_TARGET_AMBIGUOUS", "PATCH_TARGET_EMPTY", "STALE_FILE_VERSION", "EXPECTED_VERSION_REQUIRED", "PATCH_REREAD_REQUIRED", "FILE_NOT_FOUND").contains(error.code())) context.contextTrace().patchFailed(path);
            throw error;
        }
    }
}
