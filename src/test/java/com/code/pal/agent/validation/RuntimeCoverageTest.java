package com.code.pal.agent.validation;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.*;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeCoverageTest {
    private RuntimeCheckService.Result evaluate(String output,boolean started,boolean enabled) {
        return evaluate(output,started,enabled,false);
    }
    private RuntimeCheckService.Result evaluate(String output,boolean started,boolean enabled,boolean acceptance) {
        var changes=mock(ChangeSetService.class);var files=mock(ProjectFiles.class);var sandbox=mock(SandboxPort.class);var props=new AgentProperties();props.getSandbox().setRuntimeCheck(enabled);
        UUID project=UUID.randomUUID();var ctx=new AgentContext(project,7L,ProjectType.BUILD,"stage",files);
        when(changes.createValidationCandidate(ctx,"runtime")).thenReturn("candidate");when(changes.createValidationBaseline(ctx,"runtime")).thenReturn("baseline");
        when(files.regularFile(eq(project),anyString(),eq("index.html"))).thenReturn(true);
        when(sandbox.browse(eq(project),anyString())).thenReturn(new SandboxPort.CommandResult(started,started?0:-1,output,""));
        when(sandbox.browse(eq(project),anyString(),anyString())).thenReturn(new SandboxPort.CommandResult(started,started?0:-1,output,""));
        var runtime=new RuntimeCheckService(changes,new StackPacks(new ObjectMapper()),files,sandbox,mock(DependencyService.class),props,new ObjectMapper(),com.code.pal.agent.preview.BuildVariables.NONE);
        if(acceptance){var criteria=mock(RunBehaviorCriteria.class);when(criteria.current(ctx)).thenReturn(new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("result",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#result","5")),List.of(),false,false));runtime.useBehaviorCriteria(criteria);}
        return runtime.evaluate(ctx);
    }
    @Test void missingBrowserAndMalformedReportsAreSkippedRatherThanPassed() {
        for(String output:List.of("","{}","{\"issues\":[]}","{\"loaded\":true,\"issues\":{},\"interactions\":[]}"))assertTrue(evaluate(output,true,true).check().blocksSuccess());
        assertEquals(ValidationCheck.Outcome.SKIPPED,evaluate("",false,true).check().outcome());
    }
    @Test void validLoadPassesAndExplicitOptOutRemainsVisible() {
        assertEquals(ValidationCheck.Outcome.PASS,evaluate("{\"loaded\":true,\"issues\":[],\"interactions\":[]}",true,true).check().outcome());
        var disabled=evaluate("",false,false);assertEquals(ValidationCheck.Outcome.SKIPPED,disabled.check().outcome());assertFalse(disabled.check().required());
    }
    @Test void anExistingExceptionStillCannotCertifyAWorkingCandidate() {
        var result=evaluate("{\"loaded\":true,\"issues\":[{\"kind\":\"exception\",\"message\":\"Existing failure\"}],\"interactions\":[]}",true,true);
        assertEquals(ValidationCheck.Outcome.FAIL,result.check().outcome());assertEquals("PRE_EXISTING_RUNTIME_FAILURE",result.diagnostics().getFirst().code());
    }
    @Test void requiredSkippedMetadataCannotCoexistWithReportedSuccess() {
        var report=new ValidationReport(ValidationOperation.ALL,true,true,true,false,0,"npm","fixture",List.of(),List.of(),0,3,1,"fixture").withChecks(List.of(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,true,"Unavailable")));
        assertFalse(report.changeValidationPassed());assertEquals(ValidationCheck.Outcome.SKIPPED,report.outcome());
        assertTrue(new ObjectMapper().writeValueAsString(report).contains("\"outcome\":\"SKIPPED\""));
    }
    @Test void explicitBrowserAcceptanceMustBePresentAndMatchItsRequiredIds() {
        assertEquals(ValidationCheck.Outcome.SKIPPED,evaluate("{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[]}",true,true,true).check().outcome());
        assertEquals(ValidationCheck.Outcome.SKIPPED,evaluate("{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[{\"id\":\"other\",\"passed\":true}]}",true,true,true).check().outcome());
    }
    @Test void explicitBrowserOutcomeControlsCertificationRatherThanPageLoadAlone() {
        assertEquals(ValidationCheck.Outcome.PASS,evaluate("{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[{\"id\":\"result\",\"passed\":true}]}",true,true,true).check().outcome());
        var failed=evaluate("{\"loaded\":true,\"issues\":[],\"interactions\":[],\"acceptance\":[{\"id\":\"result\",\"passed\":false}]}",true,true,true);
        assertEquals(ValidationCheck.Outcome.FAIL,failed.check().outcome());assertEquals("BROWSER_ACCEPTANCE_FAILED",failed.diagnostics().getFirst().code());
    }
}
