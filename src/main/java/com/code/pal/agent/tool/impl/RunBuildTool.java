package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.validation.ValidationOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor
public class RunBuildTool implements AgentTool {
    private final ProjectValidationService validation;
    @Override public String name(){return "runBuild";}
    @Override public String description(){return "Run the existing project build script using its detected package manager against the staged workspace.";}
    @Override public boolean mutating(){return true;}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of());}
    @Override public Object execute(Map<String,Object> args,AgentContext context){return validation.run(context, ValidationOperation.BUILD,null);}
}
