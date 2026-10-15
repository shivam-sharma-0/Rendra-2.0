package com.code.pal.agent.validation;

import com.code.pal.agent.runtime.AppServerService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.database.GeneratedDatabaseService;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Candidate code and a unique scratch database; never the saved preview's API/data. */
@Service
public final class CandidateBrowserCheck {
    private final GeneratedDatabaseService databases;
    private final AppServerService servers;
    private final SandboxPort sandbox;
    public CandidateBrowserCheck(GeneratedDatabaseService databases,AppServerService servers,SandboxPort sandbox){this.databases=databases;this.servers=servers;this.sandbox=sandbox;}
    public SandboxPort.CommandResult browse(AgentContext context,String candidate,String site,String acceptance) {
        String root=".codepal/browser-api/"+context.runId()+"/"+UUID.randomUUID();
        String name="browser-"+UUID.randomUUID().toString().replace("-","");
        GeneratedDatabaseService.ValidationDatabase db=null;
        boolean starting=false,stopped=true;
        try {
            if(servers.build(context.projectId(),candidate,root)!=null)return unavailable("Candidate API did not build");
            db=databases.openValidation(context.projectId(),context.runId(),sandbox.databaseAddress());
            starting=true;
            var endpoint=sandbox.startServer(context.projectId(),name,root,List.of("node",servers.entry(context.projectId(),root)),Map.of("MONGODB_URI",db.uri(),"NODE_ENV","test"),3000);
            servers.awaitListening(context.projectId(),name,endpoint);
            return sandbox.browseWithApi(context.projectId(),site,acceptance,endpoint);
        }catch(CancellationException cancelled){throw cancelled;}
        catch(RuntimeException failed){return unavailable("Candidate API/browser infrastructure unavailable");}
        finally {
            if(starting)try{sandbox.stopServer(context.projectId(),name);}catch(RuntimeException pending){stopped=false;}
            if(stopped) {
                try{databases.closeValidation(db);sandbox.removeTree(context.projectId(),root);}
                catch(RuntimeException pending){throw new IllegalStateException("Candidate browser resource cleanup needs verification");}
            }else throw new IllegalStateException("Candidate browser process cleanup needs verification; scratch resources retained");
        }
    }
    private static SandboxPort.CommandResult unavailable(String reason){return new SandboxPort.CommandResult(false,-1,"",SandboxPort.CommandResult.NOT_STARTED+": "+reason);}
}
