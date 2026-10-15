package com.code.pal.agent.contract;
import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.route.*;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContractCoordinatorTest {
    final ObjectMapper mapper=new ObjectMapper();final LocalProject project=LocalProject.create();final AgentProperties props=project.properties;
    final ContractStore store=mock(ContractStore.class);final ApprovedProjectMemory memory=mock(ApprovedProjectMemory.class);
    final BuildDecisions decisions=mock(BuildDecisions.class);final ProjectValidationService validation=mock(ProjectValidationService.class);
    final RunBehaviorCriteria criteria=mock(RunBehaviorCriteria.class);final SequentialCheckpoints checkpoints=mock(SequentialCheckpoints.class);
    final ChangeSetService changes=new ChangeSetService(mock(FileChangeRepository.class),mock(AgentRunRepository.class),mapper,props,project.files,project.sandbox,mock(ProjectService.class));
    final ContractCoordinator coordinator=new ContractCoordinator(store,new ContractPlanner(mapper),new ContractScaffold(mapper),new UnitGenerator(mapper),memory,decisions,project.files,changes,validation,criteria,checkpoints,props,mapper);
    final BuildContract c=ContractFixtures.staticSite();final ContractStore.Binding binding=new ContractStore.Binding(1,UUID.randomUUID(),2,"source","caps",Set.of());
    ContractCoordinatorTest(){
        when(checkpoints.progress(any())).thenReturn(new SequentialCheckpoints.Progress(0,0,0,0,60000));
        when(store.current(project.projectId)).thenReturn(binding);when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(binding.requirementsProjection(),2,"Approved","Brief"));
        when(decisions.state(project.projectId)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,c.storage(),c.auth(),null),new BuildDecisions.Available(true,null),null));
        when(validation.run(any(),any(),any())).thenReturn(report(true,List.of()));
        when(validation.validateAll(any())).thenAnswer(call->{var ctx=(AgentContext)call.getArgument(0);changes.recordProjectValidation(ctx,true,List.of());return report(true,List.of(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.PASS,true,"Fixture only")));});
    }
    ValidationReport report(boolean pass,List<ValidationCheck> checks){return new ValidationReport(ValidationOperation.BUILD,true,pass,pass,false,0,"none","",List.of(),List.of(),0,3,1,"Fixture",checks);}
    AgentRun run(ProjectType mode){return AgentRun.builder().id(UUID.randomUUID()).projectId(project.projectId).userId(7L).mode(mode).status(RunStatus.RUNNING).build();}
    @Test void approvedMernUsesBoundAcceptanceWithOptionalStructuredFlagOff() {
        props.setStructuredContracts(false);
        when(decisions.state(project.projectId)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.MERN,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(true,null),null));
        var run=run(ProjectType.BUILD);assertTrue(coordinator.eligible(run,RunSize.of(SizeTier.S,List.of(),props),"Build a todo app"));
        props.setFullStackContracts(false);assertFalse(coordinator.eligible(run,RunSize.of(SizeTier.S,List.of(),props),"Build a todo app"));
    }
    AgentContext context(AgentRun run){return new AgentContext(project.projectId,7L,run.getMode(),run.getMode()==ProjectType.BUILD?changes.start(run.getId(),project.projectId):".",null,new com.code.pal.agent.context.ProjectContextTrace(),run.getId(),new com.code.pal.agent.plan.AgentPlanState(),project.files);}
    ContractStore.Stored saved(AgentRun run,String state){return new ContractStore.Stored(run.getId(),project.projectId,c,"hash",binding,state,"EXPLICIT_PLAN");}
    LlmClient.LlmResult generate(LlmClient.LlmRequest req){
        var input=mapper.readValue(req.messages().get(1).content(),UnitGenerator.Input.class);var output=new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html",input.snapshot().get("index.html").version(),"<!doctype html><main><output id='result'>5</output></main>")));
        return new LlmClient.LlmResult(mapper.writeValueAsString(output),null,List.of(),new LlmClient.Usage(30,40),"stop");
    }
    @Test void compatibleApprovedPlanAvoidsPlannerAndStagesOneScopedUnit() {
        project.write("notes.txt","Preserve me");var run=run(ProjectType.BUILD);var ctx=context(run);var plan=saved(run,"APPROVED");
        when(store.reusable(eq(project.projectId),anyString(),any())).thenReturn(plan);when(store.attach(eq(run),eq(plan),any())).thenReturn(plan);
        var calls=new AtomicInteger();var outcome=coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build the approved plan","map",r->{calls.incrementAndGet();assertEquals(CallPurpose.UNIT,LlmCallContext.current().purpose());return generate(r);},()->{},title->{});
        assertEquals(1,calls.get());assertEquals(c,outcome.contract());assertFalse(project.exists("index.html"));assertEquals("Preserve me",project.read("notes.txt"));assertFalse(changes.hasUnvalidatedChanges(ctx));
        assertEquals(com.code.pal.agent.plan.AgentPlanState.StepStatus.COMPLETED,ctx.plan().snapshot().steps().getFirst().status());verify(store,never()).create(any(),any(),any(),any());
    }

    @Test
    void attachedApprovedSmallPlanRemainsEligibleAfterBuildThisAcceptance() {
        props.setStructuredContracts(true);
        var run = run(ProjectType.BUILD);
        var approved = saved(run, "APPROVED");
        when(store.forRun(project.projectId, run.getId())).thenReturn(approved);

        assertTrue(coordinator.eligible(run, RunSize.of(SizeTier.S, List.of(), props),
                "Build the proposed changes.\n\nOriginal request:\n> Add a calculator"),
                "An attached approved S plan must not fall back to an unrelated tool-loop build");
    }

    @Test
    void acceptedPlanCannotSilentlyReplanWhenItsExactContractIsUnavailable() {
        props.setStructuredContracts(true);
        var run = run(ProjectType.BUILD);
        var ctx = context(run);
        when(store.acceptedPlanRun(run)).thenReturn(UUID.randomUUID());
        String request = "Build the proposed changes.\n\nOriginal request:\n> Add a calculator";
        assertTrue(coordinator.eligible(run, RunSize.of(SizeTier.S, List.of(), props), request));

        assertThrows(WorkflowStageException.class, () -> coordinator.execute(
                ctx, run, RunSize.of(SizeTier.S, List.of(), props), request, "map",
                call -> {
                    fail("An accepted plan must not fall through to a new planner call");
                    return null;
                },
                () -> fail("No generated output may be staged"), title -> {}));
        verify(store, never()).create(any(), any(), any(), any());
    }
    @Test void planRunReturnsReviewableContractWithoutMutationOrUnitGeneration() {
        project.write("index.html","old");var run=run(ProjectType.PLAN);var ctx=context(run);when(store.forRun(project.projectId,run.getId())).thenReturn(saved(run,"DRAFT"));
        var outcome=coordinator.execute(ctx,run,RunSize.of(SizeTier.S,List.of(),props),"plan calculator","map",r->{fail("No duplicate planning or unit call");return null;},()->fail("Plan cannot mutate"),title->{});
        assertTrue(outcome.summary().contains("Review and approve"));assertEquals("old",project.read("index.html"));verifyNoInteractions(validation,criteria,checkpoints);
    }
    @Test void recoveredOutputAvoidsAllModelCallsButStillRequiresFreshBehaviorChecks() {
        var run=run(ProjectType.BUILD);var ctx=context(run);when(store.forRun(project.projectId,run.getId())).thenReturn(saved(run,"APPROVED"));
        var cache=mock(UnitOutputCheckpoints.class);coordinator.useUnitCheckpoints(cache);
        when(cache.reuse(eq(run),any(),eq(ctx))).thenAnswer(call->{var in=(UnitGenerator.Input)call.getArgument(1);return new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html",in.snapshot().get("index.html").version(),"<main id='result'>5</main>")));});
        coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",r->{fail("Recovered unit must not spend another model call");return null;},()->{},title->{});
        verify(validation).validateAll(ctx);verify(cache).verified(run);verify(cache,never()).save(any(),any(),any());assertFalse(project.exists("index.html"));
    }
    @Test void recoveredOutputCannotBypassUnavailableBehavior() {
        var run=run(ProjectType.BUILD);var ctx=context(run);when(store.forRun(project.projectId,run.getId())).thenReturn(saved(run,"APPROVED"));
        var cache=mock(UnitOutputCheckpoints.class);coordinator.useUnitCheckpoints(cache);
        when(cache.reuse(eq(run),any(),eq(ctx))).thenAnswer(call->{var in=(UnitGenerator.Input)call.getArgument(1);return new UnitGenerator.Output(List.of(new UnitGenerator.Delta("index.html",in.snapshot().get("index.html").version(),"<main id='result'>5</main>")));});
        doReturn(report(true,List.of(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,false,"disabled")))).when(validation).validateAll(any());
        assertThrows(RuntimeException.class,()->coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",r->{fail("No model spend");return null;},()->{},title->{}));
        verify(cache,never()).verified(any());assertFalse(project.exists("index.html"));
    }
    @Test void unavailableBrowserCannotCertifyOrMarkAUnitCompleted() {
        var run=run(ProjectType.BUILD);var ctx=context(run);when(store.forRun(project.projectId,run.getId())).thenReturn(saved(run,"APPROVED"));
        doReturn(report(true,List.of(new ValidationCheck("RUNTIME",ValidationCheck.Outcome.SKIPPED,false,"disabled")))).when(validation).validateAll(any());
        assertThrows(RuntimeException.class,()->coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",this::generate,()->{},title->{}));
        assertFalse(project.exists("index.html"));assertNotEquals(com.code.pal.agent.plan.AgentPlanState.StepStatus.COMPLETED,ctx.plan().snapshot().steps().getFirst().status());
    }
    @Test void affectedRepairSelectsActualFailuresAndIgnoresDeferredChecksAndUnfailedDependents() {
        var c=ContractFixtures.mern();var report=report(false,List.of(new ValidationCheck("API_create",ValidationCheck.Outcome.FAIL,true,"failed")));
        assertEquals(List.of("data"),ContractCoordinator.affected(c,report).stream().map(BuildContract.Unit::id).toList());
        report=report(false,List.of(new ValidationCheck("BROWSER_ui",ValidationCheck.Outcome.FAIL,true,"failed")));
        assertEquals(List.of("ui"),ContractCoordinator.affected(c,report).stream().map(BuildContract.Unit::id).toList());
        report=report(false,List.of(new ValidationCheck("BROWSER_ui",ValidationCheck.Outcome.FAIL,true,"failed"),
                new ValidationCheck("API_create",ValidationCheck.Outcome.SKIPPED,true,"UI prerequisite failed",ValidationCheck.SkipCause.PREREQUISITE_FAILED)));
        assertEquals(List.of("ui"),ContractCoordinator.affected(c,report).stream().map(BuildContract.Unit::id).toList());
    }
    @Test void declaredApiCriteriaCannotBeCertifiedByPassingBrowserOnly() {
        var contract=ContractFixtures.mern();var run=run(ProjectType.BUILD);var ctx=context(run);
        when(store.forRun(project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),project.projectId,contract,"hash",binding,"APPROVED","BUILD_REQUEST"));
        assertThrows(RuntimeException.class,()->coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",request->{
            var input=mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);
            return new LlmClient.LlmResult(mapper.writeValueAsString(new UnitGenerator.Output(input.unit().ownedPaths().stream().map(p->new UnitGenerator.Delta(p,input.snapshot().get(p).version(),"export {}\n")).toList())),null,List.of(),new LlmClient.Usage(10,10),"stop");
        },()->{},title->{}));
        assertFalse(project.exists("server/package.json"));assertNotEquals(com.code.pal.agent.plan.AgentPlanState.StepStatus.COMPLETED,ctx.plan().snapshot().steps().getFirst().status());
    }
    @Test void failingUiRepairsOnlyUiWhilePassingApiRemainsUnchanged() {
        var contract=ContractFixtures.mern();var run=run(ProjectType.BUILD);var ctx=context(run);
        when(store.forRun(project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),project.projectId,contract,"hash",binding,"APPROVED","BUILD_REQUEST"));
        var validations=new AtomicInteger();
        when(validation.validateAll(ctx)).thenAnswer(call->{boolean pass=validations.incrementAndGet()>1;
            var checks=new ArrayList<ValidationCheck>();checks.add(new ValidationCheck("RUNTIME",pass?ValidationCheck.Outcome.PASS:ValidationCheck.Outcome.FAIL,true,"UI"));
            contract.acceptance().api().forEach(step->checks.add(new ValidationCheck("API_"+step.id(),ValidationCheck.Outcome.PASS,true,"API passed")));
            var diagnostics=pass?List.<ProjectDiagnostic>of():List.of(new ProjectDiagnostic("client/src/App.tsx",null,null,DiagnosticSeverity.ERROR,"Required interaction ui did not pass","runtime","BROWSER_ACCEPTANCE_FAILED"));
            changes.recordProjectValidation(ctx,pass,diagnostics);
            return new ValidationReport(ValidationOperation.BUILD,true,pass,pass,false,1,"mern","",diagnostics,List.of(),0,3,1,"Fixture",checks);});
        var calledUnits=new ArrayList<String>();
        coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",request->{
            var input=mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);calledUnits.add(input.unit().id());
            return new LlmClient.LlmResult(mapper.writeValueAsString(new UnitGenerator.Output(input.unit().ownedPaths().stream().map(path->new UnitGenerator.Delta(path,input.snapshot().get(path).version(),"export {}\n")).toList())),null,List.of(),new LlmClient.Usage(10,20),"stop");
        },()->{},title->{});
        assertEquals(List.of("data","ui","ui"),calledUnits);assertEquals(2,validations.get());assertFalse(changes.hasUnvalidatedChanges(ctx));
    }
    @Test void independentWaveMergesBeforeAnyBuildAndRequiresVerifiedScheduling() {
        var contract=BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of()),BuildContractTest.unit("style","style.css",List.of())));
        var run=run(ProjectType.BUILD);var ctx=context(run);when(store.forRun(project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),project.projectId,contract,"hash",binding,"APPROVED","BUILD_REQUEST"));
        props.setParallelUnits(true);var scheduling=props.getScheduling();scheduling.setEnabled(true);var pool=new AgentProperties.SchedulingPool();pool.setAccount("fixture");pool.setConcurrency(2);scheduling.getPools().put(props.preferredModel(ProjectType.BUILD).provider(),pool);
        var entered=new java.util.concurrent.CountDownLatch(2);var calls=new AtomicInteger();
        doAnswer(invocation->{assertEquals(2,calls.get());assertTrue(project.files.regularFile(project.projectId,ctx.root(),"index.html"));assertTrue(project.files.regularFile(project.projectId,ctx.root(),"style.css"));return report(true,List.of());}).when(validation).run(any(),any(),any());
        coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"build","map",request->{
            var in=mapper.readValue(request.messages().get(1).content(),UnitGenerator.Input.class);calls.incrementAndGet();entered.countDown();try{assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException stop){throw new LlmCancelledException();}
            var path=in.unit().ownedPaths().getFirst();return new LlmClient.LlmResult(mapper.writeValueAsString(new UnitGenerator.Output(List.of(new UnitGenerator.Delta(path,in.snapshot().get(path).version(),path.endsWith("html")?"<main id='result'>5</main>":"body { color: black }")))),null,List.of(),new LlmClient.Usage(10,20),"stop");
        },()->{},title->{});
        verify(validation,times(1)).run(any(),eq(ValidationOperation.TYPE_CHECK),isNull());verify(validation,times(1)).run(any(),eq(ValidationOperation.BUILD),isNull());assertFalse(project.exists("index.html"));
    }
    @Test void smallIndependentWaveUsesOneBatchCallAndPreservesValidationAndOwnership() {
        var contract=BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of()),BuildContractTest.unit("style","style.css",List.of())));
        var run=run(ProjectType.BUILD);var ctx=context(run);
        when(store.forRun(project.projectId,run.getId())).thenReturn(new ContractStore.Stored(run.getId(),project.projectId,contract,"hash",binding,"APPROVED","BUILD_REQUEST"));
        var calls=new AtomicInteger();
        coordinator.execute(ctx,run,RunSize.of(SizeTier.M,List.of(),props),"Build the approved plan","map",request->{
            calls.incrementAndGet();var data=mapper.readTree(request.messages().get(1).content());assertTrue(data.path("units").isArray());
            var output=new BatchUnitGenerator.Output(contract.units().stream().map(unit->new BatchUnitGenerator.UnitOutput(unit.id(),unit.ownedPaths().stream()
                    .map(path->new UnitGenerator.Delta(path,data.path("snapshot").path(path).path("version").asString(),path.endsWith("html")?"<main id='result'>5</main>":"body { color: black; }")).toList())).toList());
            return new LlmClient.LlmResult(mapper.writeValueAsString(output),null,List.of(),new LlmClient.Usage(10,20),"stop");
        },()->{},title->{});
        assertEquals(1,calls.get());assertFalse(project.exists("index.html"));assertTrue(project.files.regularFile(project.projectId,ctx.root(),"style.css"));
        verify(validation).validateAll(ctx);assertFalse(changes.hasUnvalidatedChanges(ctx));
    }
}
