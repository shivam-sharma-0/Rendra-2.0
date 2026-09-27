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
    @Override public Object execute(Map<String,Object> args,AgentContext context){return changeSets.patch(context,CreateFileTool.text(args,"path"),CreateFileTool.text(args,"expectedVersion"),CreateFileTool.text(args,"oldText"),CreateFileTool.text(args,"newText"));}
}
