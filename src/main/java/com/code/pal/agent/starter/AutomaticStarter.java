package com.code.pal.agent.starter;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.brief.ProjectBriefFile;
import java.util.*;

/** Conservative setup selection only; ambiguous/existing projects keep the ordinary inspection route. */
public final class AutomaticStarter {
    private AutomaticStarter() {}
    public static Starter select(AgentContext ctx,BuildDecisions.Choice choice,String request) {
        if(choice==null)return null;
        var entries=ctx.files().list(ctx.projectId(),ctx.root(),32,Set.of(".codepal",".git","node_modules","uploads"));
        if(entries.stream().anyMatch(e->!e.directory() && !ProjectBriefFile.privatePath(e.path()) && !e.path().equals("notes.md")))return null;
        if(choice.storage()==BuildDecisions.Storage.MERN)return Starter.MERN;
        String text=com.code.pal.agent.intent.RequestScope.normalize(request);
        if(text.matches(".*\\b(?:plain html|vanilla|no react|without react|static site)\\b.*"))return null;
        if(text.matches(".*\\b(?:react|vite|typescript|tsx)\\b.*"))return Starter.VITE_REACT;
        return null;
    }
    public static Map<String,Object> context(AgentContext ctx,Starter starter) {
        var bodies=new LinkedHashMap<String,Object>();
        var paths=starter==Starter.MERN?List.of("client/src/main.tsx","client/vite.config.ts","server/src/index.ts"):List.of("src/main.tsx","vite.config.ts");
        for(String path:paths)bodies.put(path,Map.of("version",ctx.files().version(ctx.projectId(),ctx.root(),path),"content",ctx.files().readText(ctx.projectId(),ctx.root(),path)));
        return Map.of("stack",starter.id(),"templateVersion",Starter.TEMPLATE_VERSION,"next",starter.next(),"entryFiles",bodies);
    }
}
