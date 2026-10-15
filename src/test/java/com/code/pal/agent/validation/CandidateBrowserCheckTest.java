package com.code.pal.agent.validation;

import com.code.pal.agent.runtime.AppServerService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.database.GeneratedDatabaseService;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class CandidateBrowserCheckTest {
    final GeneratedDatabaseService databases=mock(GeneratedDatabaseService.class);
    final AppServerService servers=mock(AppServerService.class);
    final SandboxPort sandbox=mock(SandboxPort.class);
    final AgentContext ctx=new AgentContext(UUID.randomUUID(),7L,ProjectType.BUILD,"stage",null,new com.code.pal.agent.context.ProjectContextTrace(),UUID.randomUUID(),new com.code.pal.agent.plan.AgentPlanState(),mock(ProjectFiles.class));
    final GeneratedDatabaseService.ValidationDatabase db=new GeneratedDatabaseService.ValidationDatabase(ctx.projectId(),ctx.runId(),"scratch","mongodb://scratch-only");
    final SandboxPort.ServerEndpoint endpoint=new SandboxPort.ServerEndpoint("http://127.0.0.1:9999",Map.of());
    final CandidateBrowserCheck check=new CandidateBrowserCheck(databases,servers,sandbox);
    void setup(){when(sandbox.databaseAddress()).thenReturn("owned-db");when(databases.openValidation(ctx.projectId(),ctx.runId(),"owned-db")).thenReturn(db);when(servers.entry(eq(ctx.projectId()),anyString())).thenReturn("dist/index.js");when(sandbox.startServer(eq(ctx.projectId()),anyString(),anyString(),anyList(),eq(Map.of("MONGODB_URI",db.uri(),"NODE_ENV","test")),eq(3000))).thenReturn(endpoint);}
    @Test void browserUsesCandidateAndScratchDataAndStopsBeforeDroppingDatabase() throws Exception {
        setup();var result=new SandboxPort.CommandResult(true,0,"{}","");when(sandbox.browseWithApi(ctx.projectId(),"site","acceptance",endpoint)).thenReturn(result);
        assertSame(result,check.browse(ctx,"candidate","site","acceptance"));
        verify(servers).build(eq(ctx.projectId()),eq("candidate"),startsWith(".codepal/browser-api/"));verify(servers).awaitListening(eq(ctx.projectId()),startsWith("browser-"),eq(endpoint));
        var order=inOrder(sandbox,databases);order.verify(sandbox).stopServer(eq(ctx.projectId()),startsWith("browser-"));order.verify(databases).closeValidation(db);
        verify(servers,never()).proxy(any(),any(),any());
    }
    @Test void unavailableTransportBlocksCoverageAndStillCleansScratchData() {
        setup();when(sandbox.browseWithApi(any(),anyString(),anyString(),any())).thenThrow(new IllegalStateException("Transport unavailable"));
        assertFalse(check.browse(ctx,"candidate","site","acceptance").started());verify(databases).closeValidation(db);
    }
    @Test void uncertainStopRetainsScratchDataInsteadOfDroppingUnderLiveProcess() {
        setup();doThrow(new IllegalStateException("Stop unavailable")).when(sandbox).stopServer(any(),anyString());
        assertThrows(IllegalStateException.class,()->check.browse(ctx,"candidate","site","acceptance"));verify(databases,never()).closeValidation(any());verify(sandbox,never()).removeTree(any(),anyString());
    }
}
