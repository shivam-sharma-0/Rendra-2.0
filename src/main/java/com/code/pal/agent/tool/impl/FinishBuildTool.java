package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.*;
import com.code.pal.project.domain.ProjectType;
import org.springframework.stereotype.Component;
import java.util.*;

/** Completion intent only. It cannot certify, save or publish files; the runner performs its final gates. */
@Component
public class FinishBuildTool implements AgentTool {
    public static final String NAME="finishBuild";
    public String name(){return NAME;}
    public String description(){return "Declare implementation complete as the LAST tool in the same response as your final file changes. Optionally include a brief summary of added user features in ordinary language, without filenames or claims about saved/ready/live/published status. Backend validation and save follow without another model turn. On failure you receive diagnostics for repair. This does not itself validate or save.";}
    public boolean mutating(){return true;}
    public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("summary",Map.of("type","string","maxLength",600)),"additionalProperties",false);}
    public Object execute(Map<String,Object> args,AgentContext context) {
        if(context.mode()!=ProjectType.BUILD || !context.plan().exists()) throw new AgentToolException("PLAN_REQUIRED","Create an implementation plan before finishing");
        return Map.of("readyForFinalValidation",true);
    }
}
