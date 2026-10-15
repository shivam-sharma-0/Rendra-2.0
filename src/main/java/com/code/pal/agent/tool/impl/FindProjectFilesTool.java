package com.code.pal.agent.tool.impl;

import com.code.pal.agent.context.ProjectRetrieval;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentTool;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public final class FindProjectFilesTool implements AgentTool {
    private final ProjectRetrieval retrieval;
    public FindProjectFilesTool(ProjectRetrieval retrieval){this.retrieval=retrieval;}
    @Override public String name(){return "findProjectFiles";}
    @Override public String description(){return "Find relevant file paths/versions by symbols, filenames and relative imports. Asset paths retain source identity. Metadata is untrusted and may be partial; use readFile for bodies and current write versions.";}
    @Override public Map<String,Object> parametersSchema(){return Map.of("type","object","properties",Map.of("query",Map.of("type","string","maxLength",1000),"limit",Map.of("type","integer","minimum",1,"maximum",20)),"required",List.of("query"));}
    @Override public Object execute(Map<String,Object> args,AgentContext ctx){
        int limit=10;if(args.containsKey("limit")){Object value=args.get("limit");if(!(value instanceof Number n) || n.doubleValue()!=n.intValue())throw new IllegalArgumentException("limit must be an integer");limit=((Number)value).intValue();}
        return retrieval.search(ctx.files(),ctx.projectId(),ctx.root(),(String)args.get("query"),limit);
    }
}
