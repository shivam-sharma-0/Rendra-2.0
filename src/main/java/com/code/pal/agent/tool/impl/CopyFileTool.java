package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Copies a file, binary included: the way an uploaded image becomes a site asset. */
@Component @RequiredArgsConstructor
public class CopyFileTool implements AgentTool {
    private final ChangeSetService changeSets;
    @Override public String name(){return "copyFile";}
    @Override public String description(){return "Copy one existing file (images and other binary files too) to an absent destination in the isolated change set, "
            + "e.g. an attached image from uploads/ into public/ (Vite) or next to index.html (static site). The source is left as it is.";}
    @Override public boolean mutating(){return true;}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("sourcePath",Map.of("type","string"),"targetPath",Map.of("type","string")),"required",List.of("sourcePath","targetPath"));}
    @Override public Object execute(Map<String,Object> args,AgentContext context){return changeSets.copy(context,CreateFileTool.text(args,"sourcePath"),CreateFileTool.text(args,"targetPath"));}
}
