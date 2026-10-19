package com.code.pal.agent;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ToolLoopAcceptance;
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
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.ApprovedSpecs;
import com.code.pal.project.spec.SpecFixtures;
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
    boolean truncatedFirst;
    boolean truncatedAlways;
    boolean supabaseValidation;
    boolean compactInventory;
    boolean unadvertisedStarter;
    boolean explicitPlanner;
    boolean plannerUnavailable;
    BuildDecisions.Choice approvedChoice;
    com.code.pal.project.spec.ApprovedSpecs specs;
    RunBehaviorCriteria criteria;
    com.code.pal.agent.contract.ContractCoordinator coordinator;
    com.code.pal.agent.vision.VisionService vision;
    final Map<String,String> existing=new LinkedHashMap<>();
    final List<String> prompts=new ArrayList<>();
    final List<Set<String>> advertisedTools = new ArrayList<>();

    @Test
    void newApplicationsUseOnePlanOnlyTurnThenTheCodingRoute() {
        explicitPlanner = true;
        var trial = trial(true, false, false);
        assertEquals(RunStatus.DONE, trial.result.status());
        assertEquals(2, trial.calls, "one planning turn and one implementing/finish turn");
        assertEquals(Set.of("setPlan"), advertisedTools.getFirst());
        assertEquals(CALCULATOR, trial.code);
    }

    @Test
    void unavailablePlannerCountsBothAttemptsBeforeOneCodingTurn() {
        explicitPlanner = true;
        plannerUnavailable = true;
        var trial = trial(true, false, false);
        assertEquals(RunStatus.DONE, trial.result.status());
        assertEquals(3, trial.calls);
        assertEquals(3, trial.result.modelCalls(), "failed primary remains counted; fallback is not a refund");
        assertEquals(Set.of("setPlan"), advertisedTools.get(0));
        assertEquals(Set.of("setPlan"), advertisedTools.get(1));
        assertEquals(CALCULATOR, trial.code);
    }

    @Test
    void capabilityCatalogRemovesExistingSetupWithoutAddingASelectionCall() {
        compactInventory = true;
        existing.put("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        var result = trial(true, false, false);
        assertEquals(RunStatus.DONE, result.result.status());
        assertEquals(1, result.calls);
        assertEquals(CALCULATOR, result.code);
        assertEquals(Set.of("createFile", "finishBuild", "setPlan"), advertisedTools.getFirst());
    }

    @Test
    void anUnadvertisedRegisteredToolCannotRunOrCompleteTheBuild() {
        compactInventory = true;
        unadvertisedStarter = true;
        existing.put("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        var result = trial(true, false, false);
        assertEquals(RunStatus.DONE, result.result.status());
        assertEquals(2, result.calls, "an unavailable tool must prevent same-turn completion");
        assertTrue(advertisedTools.stream().noneMatch(names -> names.contains("startProject")));
        assertTrue(prompts.stream().anyMatch(value -> value.contains("TOOL_NOT_AVAILABLE")));
        assertEquals(CALCULATOR, result.code);
    }

    @Test
    void attachedRequirementsReachTheFullStackCoordinatorWithoutEnteringTheFrontendToolLoop() {
        coordinator = mock(com.code.pal.agent.contract.ContractCoordinator.class);
        vision = mock(com.code.pal.agent.vision.VisionService.class);
        when(vision.describe(any(), anyList(), anyString())).thenReturn(List.of(
                new com.code.pal.agent.vision.VisionService.Brief("uploads/reference.png", "Calendar with appointment editor", null)));
        when(coordinator.eligible(any(), any(), anyString(), any())).thenReturn(true);
        when(coordinator.execute(any(), any(), any(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    String request = call.getArgument(3);
                    assertTrue(request.contains("Build an addition calculator"));
                    assertTrue(request.contains("Calendar with appointment editor"));
                    assertTrue(request.contains("uploads/reference.png"));
                    throw new com.code.pal.project.exception.WorkflowStageException("Fixture ends before generation");
                });
        var result = trial(true, false, false);
        assertEquals(RunStatus.FAILED, result.result.status());
        assertEquals(0, result.calls, "attachments must not silently bypass the full-stack acceptance route");
        verify(coordinator).execute(any(), any(), any(), anyString(), anyString(), any(), any(), any(), any());
        assertNull(result.code);
    }

    @Test
    void truncatedToolBatchesDoNotExecuteEvenWhenAnEarlierCallInTheBatchIsValid() {
        truncatedFirst = true;
        var result = trial(true, false, false);
        assertEquals(RunStatus.DONE, result.result.status());
        assertEquals(2, result.calls);
        assertEquals(CALCULATOR, result.code);
        assertTrue(prompts.stream().anyMatch(value -> value.contains("None of that response's tools were executed")));
    }

    @Test
    void repeatedTruncationStopsWithoutSavingPartialToolOutput() {
        truncatedAlways = true;
        var result = trial(true, false, false);
        assertEquals(RunStatus.FAILED, result.result.status());
        assertEquals(2, result.calls);
        assertNull(result.code);
    }

    @Test void aDirectChangeEditsAndFinishesWithoutASetPlanTurn() {
        skipPlan=true;existing.put("README.md","Calculator notes");
        var trial=trial(true,false,false);
        assertEquals(RunStatus.DONE,trial.result.status());assertEquals(1,trial.calls);assertEquals(CALCULATOR,trial.code);
    }
    @Test void disabledCloudBrowserAdapterDoesNotInstallAFrontendOnlyRecipe() {
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

    @Test
    void enabledCloudAdapterAddsAdvisoryBrowserGuidanceWithoutAnotherModelCall() {
        supabaseValidation = true;
        approvedChoice = new BuildDecisions.Choice(1, BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.ACCOUNTS, "connection-revision");
        var cloud = SpecFixtures.envelope(SpecFixtures.approved().spec(), new ApprovedSpec.Desired(
                ApprovedSpec.RenderStack.VITE, ApprovedSpec.Storage.SUPABASE, ApprovedSpec.Auth.ACCOUNTS), ApprovedSpec.Readiness.READY);
        specs = mock(ApprovedSpecs.class);
        when(specs.current(any())).thenReturn(Optional.of(cloud));
        criteria = mock(RunBehaviorCriteria.class);
        var trial = trial(true, false, false);
        assertEquals(1, trial.calls);
        verify(criteria).install(any(), any(), eq(cloud.requirementsProjection()), eq(cloud.requirementsRevision()),
                argThat(recipe -> recipe.modelSpec() && recipe.api().isEmpty() && !recipe.authRequired()));
        assertTrue(prompts.contains(ToolLoopAcceptance.promptSection(ToolLoopAcceptance.browserForSupabase(cloud).orElseThrow())));
    }

    @Test
    void unavailableCloudValidationStopsBeforeTheFirstModelCallOrFileGeneration() {
        approvedChoice = new BuildDecisions.Choice(1, BuildDecisions.Storage.SUPABASE, BuildDecisions.Auth.NONE, "connection-revision");
        var trial = trial(true, false, false);
        assertEquals(RunStatus.FAILED, trial.result.status());
        assertEquals(0, trial.calls);
        assertNull(trial.code);
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
        if (explicitPlanner) props.getModels().setPlan(List.of("openrouter:nvidia/nemotron-3-ultra-550b-a55b:free"));
        if (plannerUnavailable) props.getModels().setPlan(List.of("openrouter:nvidia/nemotron-3-ultra-550b-a55b:free", "nvidia:planning-fallback"));
        props.getSupabaseValidation().setEnabled(supabaseValidation);
        props.setCompactToolCatalog(compactInventory);
        if (approvedChoice != null) {
            props.setAutomaticStarter(false); // This fixture isolates admission/recipe wiring from the separately tested starter.
            props.getSandbox().setRequired(true);
        }
        var workspace=LocalProject.create(props);var mapper=new ObjectMapper();var runs=mock(AgentRunRepository.class);
        when(runs.save(any())).thenAnswer(c->c.getArgument(0));when(runs.beginCommit(any(),any(),any())).thenReturn(1);
        when(runs.recordSnapshot(any(),any())).thenReturn(1);when(runs.recordCodeSaved(any(),any())).thenReturn(1);
        var rights=mock(ProjectService.class);var project=Project.builder().id(workspace.projectId).workspaceId(UUID.randomUUID()).ownerId(7L).name("Calculator")
                .type(ProjectType.BUILD).deploymentStatus(DeploymentStatus.NONE).lastActivityAt(Instant.now()).build();
        when(rights.requireAccessible(7L,workspace.projectId)).thenReturn(project);when(rights.requireCanEdit(7L,workspace.projectId)).thenReturn(project);
        var changes=new ChangeSetService(mock(FileChangeRepository.class),runs,mapper,props,workspace.files,workspace.sandbox,rights);
        var validation=validation(workspace,changes,mapper,validationFailure);
        var starter = spy(new StartProjectTool(changes));
        var registry=new ToolRegistry(List.of(new SetPlanTool(),new CreateFileTool(changes),new FinishBuildTool(),starter));
        var executor=new AgentToolExecutor(props,registry,rights);var count=new AtomicInteger();
        LlmClient model=(request,delta)->{prompts.addAll(request.messages().stream().map(m->m.content()==null?"":m.content()).toList());
            advertisedTools.add(request.tools().stream().map(LlmClient.ToolSpec::name).collect(java.util.stream.Collectors.toSet()));
            int call=count.incrementAndGet();if(providerStatus!=null){
                if(providerStatus==0)throw new com.code.pal.agent.llm.LlmQuotaExceededException("This run's token budget is exhausted");
                if(providerStatus==-1)throw new com.code.pal.agent.llm.LlmQuotaExceededException("Daily AI limit reached");
                throw new com.code.pal.agent.llm.LlmHttpException(providerStatus,"Rejected fixture request");
            }
            if (explicitPlanner && plannerUnavailable && call == 1) {
                throw new com.code.pal.agent.llm.LlmStreamException(com.code.pal.agent.llm.LlmStreamException.Reason.PROVIDER_ERROR, 503, null);
            }
            if (explicitPlanner && call == (plannerUnavailable ? 2 : 1)) {
                assertEquals(plannerUnavailable ? "nvidia" : "openrouter", request.provider());
                assertEquals(plannerUnavailable ? "planning-fallback" : "nvidia/nemotron-3-ultra-550b-a55b:free", request.model());
                return new LlmClient.LlmResult("", null, List.of(new LlmClient.ToolCall("plan", "setPlan",
                        "{\"steps\":[\"index.html implements the calculator\",\"Validate inputs and results\"]}")),
                        new LlmClient.Usage(20, 40), "tool_calls");
            }
            if (explicitPlanner) assertEquals("test", request.model());
            if (truncatedAlways || truncatedFirst && call == 1) {
                return new LlmClient.LlmResult("", null, List.of(
                        new LlmClient.ToolCall("partial", "createFile", mapper.writeValueAsString(
                                Map.of("path", "index.html", "content", "partial"))),
                        new LlmClient.ToolCall("cut", "createFile", "{\"path\":")), new LlmClient.Usage(20, 40), "length");
            }
            if(call==1 || truncatedFirst && call==2 || explicitPlanner && call==(plannerUnavailable ? 3 : 2)) {
                var tools=new ArrayList<LlmClient.ToolCall>();
                if(!react && !skipPlan)tools.add(new LlmClient.ToolCall("plan","setPlan","{\"steps\":[\"Create calculator\",\"Validate\"]}"));
                if(react) {
                    assertTrue(request.messages().stream().anyMatch(m->m.content()!=null && m.content().contains("Backend prepared the approved starter")));
                    tools.add(new LlmClient.ToolCall("code","createFile","{\"path\":\"src/App.tsx\",\"content\":\"export default function App(){return <main>Calculator</main>}\"}"));
                }else tools.add(new LlmClient.ToolCall("code","createFile",mapper.writeValueAsString(Map.of("path","index.html","content",CALCULATOR))));
                if(toolFailure)tools.add(new LlmClient.ToolCall("invalid","createFile",mapper.writeValueAsString(Map.of("path","../escape.html","content","invalid"))));
                if(unadvertisedStarter)tools.add(new LlmClient.ToolCall("unavailable","startProject","{\"stack\":\"static-site\"}"));
                if(finish)tools.add(new LlmClient.ToolCall("finish","finishBuild","{}"));
                return new LlmClient.LlmResult("",null,tools,new LlmClient.Usage(20,40),"tool_calls");
            }
            if(toolFailure || unadvertisedStarter){assertEquals(2,call);return new LlmClient.LlmResult("",null,List.of(new LlmClient.ToolCall("confirm","finishBuild","{}")),new LlmClient.Usage(20,10),"tool_calls");}
            if(finish)throw new AssertionError("Completion must not send a summary/polish call");
            return new LlmClient.LlmResult("Calculator is ready",null,List.of(),new LlmClient.Usage(20,10),"stop");
        };
        var run=AgentRun.builder().id(UUID.randomUUID()).projectId(workspace.projectId).userId(7L).mode(ProjectType.BUILD).messageId("completion-fixture").status(RunStatus.QUEUED).build();
        var messages=mock(MessageRepository.class);
        var requestMessage=Message.user(workspace.projectId,"completion-user",react?"Build a React calculator":"Build an addition calculator",ProjectType.BUILD,7L);
        if(vision!=null)requestMessage.setAttachments("uploads/reference.png");
        com.code.pal.testsupport.QueuedRequestFixture.stubOriginal(messages,run,requestMessage);
        var heartbeat=mock(AgentHeartbeat.class);when(heartbeat.start(any(),any())).thenReturn(()->{});
        var sites=previewResult==null?null:mock(com.code.pal.agent.preview.SiteBuildService.class);
        if(sites!=null)when(sites.build(workspace.projectId)).thenReturn(previewResult);
        var runner=new AgentRunner(model,registry,executor,mock(AgentEvents.class),props,new ProjectContextService(props,mapper),runs,rights,messages,
                mock(com.code.pal.realtime.RealtimeService.class),workspace.files,heartbeat,mock(AgentJobQueue.class),mapper,changes,validation,mock(com.code.pal.notification.service.NotificationService.class),null,null,sites,vision,null,null,null);
        if (explicitPlanner) runner.useImplementationPlanning(new com.code.pal.agent.plan.ImplementationPlanning());
        if(coordinator!=null) {
            var checkpoints=mock(SequentialCheckpoints.class);
            var progress=new SequentialCheckpoints.Progress(0,0,0,0,120000);
            when(checkpoints.configure(any(),any())).thenReturn(progress);
            when(checkpoints.progress(any())).thenReturn(progress);
            runner.useCheckpoints(checkpoints);
            runner.useContractCoordinator(coordinator);
        }
        if(react) {
            var decisions=mock(com.code.pal.project.capability.BuildDecisions.class);
            when(decisions.state(workspace.projectId)).thenReturn(new com.code.pal.project.capability.BuildDecisions.State(
                    new com.code.pal.project.capability.BuildDecisions.Choice(1,com.code.pal.project.capability.BuildDecisions.Storage.BROWSER,com.code.pal.project.capability.BuildDecisions.Auth.NONE,null),
                    new com.code.pal.project.capability.BuildDecisions.Available(true,null),null));runner.setBuildDecisions(decisions);
        }
        if (approvedChoice != null) {
            var decisions = mock(BuildDecisions.class);
            when(decisions.state(workspace.projectId)).thenReturn(new BuildDecisions.State(approvedChoice,
                    new BuildDecisions.Available(true, approvedChoice.connectionRevision()), null));
            runner.setBuildDecisions(decisions);
        }
        if(specs!=null)runner.useApprovedSpecs(specs);if(criteria!=null)runner.useBehaviorCriteria(criteria);
        if (compactInventory) runner.useToolInventory(new CapabilityToolInventory(props,
                new com.code.pal.agent.stack.StackPacks(mapper), workspace.files,
                criteria == null ? mock(RunBehaviorCriteria.class) : criteria));
        existing.forEach(workspace::write);
        var result=runner.run(run);
        if (unadvertisedStarter) verify(starter, never()).execute(anyMap(), any());
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
