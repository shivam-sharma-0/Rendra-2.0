package com.code.pal.agent;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.*;
import com.code.pal.agent.tool.impl.*;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.message.domain.Message;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.*;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentCallCountTest {
    static final String CALCULATOR="""
            <!doctype html><html><head><title>Addition calculator</title></head><body><main>
            <h1>Calculator</h1><label>First number <input id="a" type="number" value="2"></label>
            <label>Second number <input id="b" type="number" value="3"></label><button id="calc">Add</button>
            <output id="result">0</output></main><script>document.querySelector('#calc').onclick=()=>{
            document.querySelector('#result').textContent=String(Number(document.querySelector('#a').value)+Number(document.querySelector('#b').value));};</script></body></html>
            """;
    record Trial(com.code.pal.agent.runtime.AgentRunResult result,String code,int calls,AgentFailureReason failureReason,String previewStatus) {}
    boolean skipPlan;
    com.code.pal.project.spec.ApprovedSpecs specs;
    RunBehaviorCriteria criteria;
    final Map<String,String> existing=new LinkedHashMap<>();
    final List<String> prompts=new ArrayList<>();

    @Test void aDirectChangeEditsAndFinishesWithoutASetPlanTurn() {
        skipPlan=true;existing.put("README.md","Calculator notes");
        var trial=trial(true,false,false);
        assertEquals(RunStatus.DONE,trial.result.status());assertEquals(1,trial.calls);assertEquals(CALCULATOR,trial.code);
    }
    @Test void aCloudDataSpecIsBuiltUnverifiedInsteadOfFailingOrFakingBrowserCertification() {
        var browser=com.code.pal.project.spec.SpecFixtures.approved();
        var cloud=com.code.pal.project.spec.SpecFixtures.envelope(browser.spec(),new com.code.pal.project.spec.ApprovedSpec.Desired(
                com.code.pal.project.spec.ApprovedSpec.RenderStack.VITE,com.code.pal.project.spec.ApprovedSpec.Storage.SUPABASE,
                com.code.pal.project.spec.ApprovedSpec.Auth.NONE),com.code.pal.project.spec.ApprovedSpec.Readiness.READY);
        specs=mock(com.code.pal.project.spec.ApprovedSpecs.class);when(specs.current(any())).thenReturn(Optional.of(cloud));
        criteria=mock(RunBehaviorCriteria.class);
        var trial=trial(true,false,false);
        assertEquals(RunStatus.DONE,trial.result.status());assertEquals(1,trial.calls);
        verify(criteria,never()).install(any(),any(),any(),anyLong(),any());
        var recipe=com.code.pal.agent.contract.ToolLoopAcceptance.promptSection(com.code.pal.agent.contract.ToolLoopAcceptance.from(browser).orElseThrow());
        assertFalse(prompts.contains(recipe),"no browser recipe is offered for cloud data");
    }
    @Test void aResumedRunWhoseApprovedSpecVanishedStopsInsteadOfBuildingBlind() {
        var compiled=com.code.pal.agent.contract.ToolLoopAcceptance.from(com.code.pal.project.spec.SpecFixtures.approved()).orElseThrow();
        specs=mock(com.code.pal.project.spec.ApprovedSpecs.class);when(specs.current(any())).thenReturn(Optional.empty());
        criteria=mock(RunBehaviorCriteria.class);when(criteria.current(any())).thenReturn(compiled.criteria());
        existing.put("package.json","{\"name\":\"calculator\"}");
        var trial=trial(true,false,false);
        assertEquals(RunStatus.FAILED,trial.result.status());assertEquals(0,trial.calls,"no model call without the recipe");
        assertNull(trial.code);
    }
    @Test void aResumedRunGetsTheSameGuidanceForTheChecksItAlreadyInstalled() {
        var approved=com.code.pal.project.spec.SpecFixtures.approved();
        var compiled=com.code.pal.agent.contract.ToolLoopAcceptance.from(approved).orElseThrow();
        specs=mock(com.code.pal.project.spec.ApprovedSpecs.class);when(specs.current(any())).thenReturn(Optional.of(approved));
        criteria=mock(RunBehaviorCriteria.class);when(criteria.current(any())).thenReturn(compiled.criteria());
        existing.put("package.json","{\"name\":\"calculator\"}");
        var trial=trial(true,false,false);
        assertEquals(RunStatus.DONE,trial.result.status());assertEquals(1,trial.calls);
        assertTrue(prompts.contains(com.code.pal.agent.contract.ToolLoopAcceptance.promptSection(compiled)),"staged files no longer hide the recipe");
        verify(criteria,never()).install(any(),any(),any(),anyLong(),any());
    }
    @Test void completionInTheCodeTurnAvoidsFinalSummaryAndDefaultPolishCalls() {
        var before=trial(false,true,false);var after=trial(true,false,false);
        assertEquals(RunStatus.DONE,before.result.status());assertEquals(RunStatus.DONE,after.result.status());
        assertEquals(before.code,after.code);assertEquals(3,before.calls);assertEquals(1,after.calls);
        assertEquals(1,after.result.modelCalls());assertFalse(new AgentProperties().isPolishTurn());
    }
    @Test void finishDeclarationCannotCertifyFailingValidationOrSaveSource() {
        var failed=trial(true,false,true);assertEquals(RunStatus.FAILED,failed.result.status());assertEquals(1,failed.calls);assertNull(failed.code);
    }
    @Test void approvedReactSetupIsPreparedBeforeTheOnlyGenerationCall() {
        var trial=trial(true,false,false,true);assertEquals(RunStatus.DONE,trial.result.status());assertEquals(1,trial.calls);
        assertTrue(trial.code.contains("root"));assertEquals(3,trial.result.toolCalls());
    }
    @Test void failedToolPreventsSameTurnCompletionEvenWhenOtherFilesAreValid() {
        var trial=trial(true,false,false,false,true);
        assertEquals(RunStatus.DONE,trial.result.status());assertEquals(2,trial.calls);
        assertEquals(CALCULATOR,trial.code);
    }
    @Test void invalidProviderPayloadTerminatesWithoutRepeatedModelFallback() {
        for(int status:List.of(400,413,422)){var trial=trial(true,false,false,false,false,status);assertEquals(RunStatus.FAILED,trial.result.status());assertEquals(1,trial.calls);assertNull(trial.code);}
    }
    @Test void staticPreviewNeedsNoCompileAndActualPreviewFailuresStayDistinct() {
        var ready=trial(true,false,false,false,false,null,new com.code.pal.agent.preview.SiteBuildService.Result(false,null));
        assertEquals(RunStatus.DONE,ready.result.status());assertEquals("READY",ready.previewStatus);
        var failed=trial(true,false,false,false,false,null,new com.code.pal.agent.preview.SiteBuildService.Result(false,"Fixture preview failure"));
        assertEquals(RunStatus.DONE,failed.result.status());assertEquals("FAILED",failed.previewStatus);
    }
    @Test void runBudgetAndDailyQuotaRemainDistinctThroughProviderFailureHandling() {
        var budget=trial(true,false,false,false,false,0);assertEquals(1,budget.calls);assertEquals(AgentFailureReason.RUN_TOKEN_BUDGET_EXCEEDED,budget.failureReason);
        var daily=trial(true,false,false,false,false,-1);assertEquals(1,daily.calls);assertEquals(AgentFailureReason.DAILY_QUOTA_EXCEEDED,daily.failureReason);
    }
    Trial trial(boolean finish,boolean polish,boolean validationFailure) {
        return trial(finish,polish,validationFailure,false);
    }
    Trial trial(boolean finish,boolean polish,boolean validationFailure,boolean react) {
        return trial(finish,polish,validationFailure,react,false);
    }
    Trial trial(boolean finish,boolean polish,boolean validationFailure,boolean react,boolean toolFailure) {
        return trial(finish,polish,validationFailure,react,toolFailure,null);
    }
    Trial trial(boolean finish,boolean polish,boolean validationFailure,boolean react,boolean toolFailure,Integer providerStatus) {
        return trial(finish,polish,validationFailure,react,toolFailure,providerStatus,null);
    }
    Trial trial(boolean finish,boolean polish,boolean validationFailure,boolean react,boolean toolFailure,Integer providerStatus,com.code.pal.agent.preview.SiteBuildService.Result previewResult) {
        var props=new AgentProperties();props.setPolishTurn(polish);props.getModels().setBuild(List.of("test"));
        var workspace=LocalProject.create(props);var mapper=new ObjectMapper();var runs=mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(c->c.getArgument(0));when(runs.beginCommit(any(),any(),any())).thenReturn(1);
        when(runs.recordSnapshot(any(),any())).thenReturn(1);when(runs.recordCodeSaved(any(),any())).thenReturn(1);
        var rights=mock(ProjectService.class);var project=Project.builder().id(workspace.projectId).workspaceId(UUID.randomUUID()).ownerId(7L).name("Calculator")
                .type(ProjectType.BUILD).deploymentStatus(DeploymentStatus.NONE).lastActivityAt(Instant.now()).build();
        when(rights.requireAccessible(7L,workspace.projectId)).thenReturn(project);when(rights.requireCanEdit(7L,workspace.projectId)).thenReturn(project);
        var changes=new ChangeSetService(mock(FileChangeRepository.class),runs,mapper,props,workspace.files,workspace.sandbox,rights);
        var validation=validation(workspace,changes,mapper,validationFailure);
        var registry=new ToolRegistry(List.of(new SetPlanTool(),new CreateFileTool(changes),new FinishBuildTool(),new StartProjectTool(changes)));
        var executor=new AgentToolExecutor(props,registry,rights);var count=new AtomicInteger();
        LlmClient model=(request,delta)->{prompts.addAll(request.messages().stream().map(m->m.content()==null?"":m.content()).toList());
            int call=count.incrementAndGet();if(providerStatus!=null){
                if(providerStatus==0)throw new com.code.pal.agent.llm.LlmQuotaExceededException("This run's token budget is exhausted");
                if(providerStatus==-1)throw new com.code.pal.agent.llm.LlmQuotaExceededException("Daily AI limit reached");
                throw new com.code.pal.agent.llm.LlmHttpException(providerStatus,"Rejected fixture request");
            }if(call==1) {
                var tools=new ArrayList<LlmClient.ToolCall>();
                if(!react && !skipPlan)tools.add(new LlmClient.ToolCall("plan","setPlan","{\"steps\":[\"Create calculator\",\"Validate\"]}"));
                if(react) {
                    assertTrue(request.messages().stream().anyMatch(m->m.content()!=null && m.content().contains("Backend prepared the approved starter")));
                    tools.add(new LlmClient.ToolCall("code","createFile","{\"path\":\"src/App.tsx\",\"content\":\"export default function App(){return <main>Calculator</main>}\"}"));
                }else tools.add(new LlmClient.ToolCall("code","createFile",mapper.writeValueAsString(Map.of("path","index.html","content",CALCULATOR))));
                if(toolFailure)tools.add(new LlmClient.ToolCall("invalid","createFile",mapper.writeValueAsString(Map.of("path","../escape.html","content","invalid"))));
                if(finish)tools.add(new LlmClient.ToolCall("finish","finishBuild","{}"));
                return new LlmClient.LlmResult("",null,tools,new LlmClient.Usage(20,40),"tool_calls");
            }
            if(toolFailure){assertEquals(2,call);return new LlmClient.LlmResult("",null,List.of(new LlmClient.ToolCall("confirm","finishBuild","{}")),new LlmClient.Usage(20,10),"tool_calls");}
            if(finish)throw new AssertionError("Completion must not send a summary/polish call");
            return new LlmClient.LlmResult("Calculator is ready",null,List.of(),new LlmClient.Usage(20,10),"stop");
        };
        var run=AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L).mode(ProjectType.BUILD).messageId("completion-fixture").status(RunStatus.QUEUED).build();
        var messages=mock(MessageRepository.class);com.code.pal.testsupport.QueuedRequestFixture.stubOriginal(messages,run,Message.user(workspace.projectId,"completion-user",react?"Build a React calculator":"Build an addition calculator",ProjectType.BUILD,7L));
        var heartbeat=mock(AgentHeartbeat.class);when(heartbeat.start(any(),any())).thenReturn(()->{});
        var sites=previewResult==null?null:mock(com.code.pal.agent.preview.SiteBuildService.class);
        if(sites!=null)when(sites.build(workspace.projectId)).thenReturn(previewResult);
        var runner=new AgentRunner(model,registry,executor,mock(AgentEvents.class),props,new ProjectContextService(props,mapper),runs,rights,messages,
                mock(com.code.pal.realtime.RealtimeService.class),workspace.files,heartbeat,mock(AgentJobQueue.class),mapper,changes,validation,mock(com.code.pal.notification.service.NotificationService.class),null,null,sites,null,null,null,null);
        if(react) {
            var decisions=mock(com.code.pal.project.capability.BuildDecisions.class);
            when(decisions.state(workspace.projectId)).thenReturn(new com.code.pal.project.capability.BuildDecisions.State(
                    new com.code.pal.project.capability.BuildDecisions.Choice(1,com.code.pal.project.capability.BuildDecisions.Storage.BROWSER,com.code.pal.project.capability.BuildDecisions.Auth.NONE,null),
                    new com.code.pal.project.capability.BuildDecisions.Available(true,null),null));runner.setBuildDecisions(decisions);
        }
        if(specs!=null)runner.useApprovedSpecs(specs);if(criteria!=null)runner.useBehaviorCriteria(criteria);
        existing.forEach(workspace::write);
        var result=runner.run(run);
        return new Trial(result,workspace.exists("index.html")?workspace.read("index.html"):null,count.get(),run.getFailureReason(),run.getPreviewStatus());
    }
    ProjectValidationService validation(LocalProject workspace,ChangeSetService changes,ObjectMapper mapper,boolean fail) {
        var validation=mock(ProjectValidationService.class);
        when(validation.validateAll(any())).thenAnswer(call->{var ctx=(AgentContext)call.getArgument(0);
            if(fail)changes.recordTerminalValidationFailure(ctx,List.of());else changes.recordProjectValidation(ctx,true,List.of());
            return new ValidationReport(ValidationOperation.BUILD,true,!fail,!fail,false,1,"static","",List.of(),List.of(),0,3,1,"Fixture checks",List.of());});
        return validation;
    }
}
