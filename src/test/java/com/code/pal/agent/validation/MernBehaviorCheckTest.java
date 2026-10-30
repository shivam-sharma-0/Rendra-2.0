package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
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

class MernBehaviorCheckTest {
    @Test void missingCriteriaDoNotStartOrMutateTheLiveServerOrDatabase() {
        var criteria=mock(RunBehaviorCriteria.class);var db=mock(GeneratedDatabaseService.class);var server=mock(AppServerService.class);var sandbox=mock(SandboxPort.class);var changes=mock(ChangeSetService.class);
        var ctx=new AgentContext(UUID.randomUUID(),7L,ProjectType.BUILD,"stage",mock(ProjectFiles.class));
        var result=new MernBehaviorCheck(criteria,db,server,sandbox,changes,mock(ApiBehaviorProbe.class)).check(ctx);
        assertEquals(ValidationCheck.Outcome.SKIPPED,result.getFirst().outcome());assertTrue(result.getFirst().required());verifyNoInteractions(db,server,sandbox,changes);
    }
    @Test void anEditOfASavedAppRunsTheSavedAppsApiChecks() {
        // An edit has no acceptance of its own; skipping would block every follow-up on a full-stack app.
        var criteria=mock(RunBehaviorCriteria.class);var db=mock(GeneratedDatabaseService.class);var server=mock(AppServerService.class);var sandbox=mock(SandboxPort.class);var changes=mock(ChangeSetService.class);
        var ctx=new AgentContext(UUID.randomUUID(),7L,ProjectType.BUILD,"stage",mock(ProjectFiles.class));
        when(criteria.regression(ctx)).thenReturn(new BehaviorCriteria(1,List.of(),List.of(BehaviorCriteriaTest.step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",200)),false,false));
        when(changes.createValidationCandidate(ctx,"api-behavior")).thenReturn("candidate");when(server.build(eq(ctx.projectId()),eq("candidate"),anyString())).thenReturn("Build failed");
        var result=new MernBehaviorCheck(criteria,db,server,sandbox,changes,mock(ApiBehaviorProbe.class)).check(ctx);
        verify(changes).createValidationCandidate(ctx,"api-behavior");
        assertTrue(result.stream().anyMatch(c->c.id().equals("API_BEHAVIOR") && c.outcome()==ValidationCheck.Outcome.FAIL),"the inherited checks ran");
    }
    @Test void buildFailureStillReportsCleanupFailureInsteadOfHidingIt() {
        var criteria=mock(RunBehaviorCriteria.class);var db=mock(GeneratedDatabaseService.class);var server=mock(AppServerService.class);var sandbox=mock(SandboxPort.class);var changes=mock(ChangeSetService.class);
        var ctx=new AgentContext(UUID.randomUUID(),7L,ProjectType.BUILD,"stage",mock(ProjectFiles.class));
        when(criteria.current(ctx)).thenReturn(new BehaviorCriteria(1,List.of(),List.of(BehaviorCriteriaTest.step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",200)),false,false));
        when(changes.createValidationCandidate(ctx,"api-behavior")).thenReturn("candidate");when(server.build(eq(ctx.projectId()),eq("candidate"),anyString())).thenReturn("Build failed");
        doThrow(new IllegalStateException("Unavailable")).when(sandbox).stopServer(eq(ctx.projectId()),anyString());
        var result=new MernBehaviorCheck(criteria,db,server,sandbox,changes,mock(ApiBehaviorProbe.class)).check(ctx);
        assertTrue(result.stream().anyMatch(c->c.id().equals("API_BEHAVIOR") && c.outcome()==ValidationCheck.Outcome.FAIL));
        assertTrue(result.stream().anyMatch(c->c.id().equals("API_CLEANUP") && c.outcome()==ValidationCheck.Outcome.FAIL));
        verify(db,never()).openValidation(any(),any(),anyString());
        verify(db,never()).closeValidation(any());verify(sandbox,never()).removeTree(any(),anyString());
    }
}
