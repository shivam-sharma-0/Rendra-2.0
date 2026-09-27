package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.validation.ValidationOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor
public class RunTestsTool implements AgentTool {
    private final ProjectValidationService validation;
    @Override public String name(){return "runTests";}
    @Override public String description(){return "Run the existing project test script, optionally limited to a safe relative test path or test name.";}
    @Override public boolean mutating(){return true;}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("scope",Map.of("type","string")));}
    @Override public Object execute(Map<String,Object> args,AgentContext context){return validation.run(context,ValidationOperation.TESTS,args.get("scope")==null?null:args.get("scope").toString());}
}
