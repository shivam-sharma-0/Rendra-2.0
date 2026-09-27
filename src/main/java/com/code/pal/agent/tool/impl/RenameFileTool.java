package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component @RequiredArgsConstructor
public class RenameFileTool implements AgentTool {
    private final ChangeSetService changeSets;
    @Override public String name(){return "renameFile";}
    @Override public String description(){return "Rename one existing file in the isolated change set. Requires its exact version and an absent destination.";}
    @Override public boolean mutating(){return true;}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("sourcePath",Map.of("type","string"),"targetPath",Map.of("type","string"),"expectedVersion",Map.of("type","string")),"required",List.of("sourcePath","targetPath","expectedVersion"));}
    @Override public Object execute(Map<String,Object> args,AgentContext context){return changeSets.rename(context,CreateFileTool.text(args,"sourcePath"),CreateFileTool.text(args,"targetPath"),CreateFileTool.text(args,"expectedVersion"));}
}
