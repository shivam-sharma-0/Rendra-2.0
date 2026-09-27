package com.code.pal.agent.tool.impl;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.validation.ProjectValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component @RequiredArgsConstructor
public class GetDiagnosticsTool implements AgentTool {
    private final ProjectValidationService validation;
    @Override public String name(){return "getDiagnostics";}
    @Override public String description(){return "Inspect detected package manager/scripts, the latest bounded diagnostics, and remaining repair budget.";}
    @Override public boolean mutating(){return true; /* ordered BUILD-only state operation */}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of());}
    @Override public Object execute(Map<String,Object> args,AgentContext context){return validation.diagnostics(context);}
}
