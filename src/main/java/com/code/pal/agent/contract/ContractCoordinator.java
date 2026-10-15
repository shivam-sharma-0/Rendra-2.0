package com.code.pal.agent.contract;
import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.route.*;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.exception.WorkflowStageException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.*;

/** Structured route; one coordinator owns mutation, validation and final save authority. */
@Service
public final class ContractCoordinator {
    private final ContractStore store;private final ContractPlanner planner;private final ContractScaffold scaffold;private final UnitGenerator generator;
    private final ApprovedProjectMemory memory;private final BuildDecisions decisions;private final ProjectFiles files;private final ChangeSetService changes;
    private final ProjectValidationService validation;private final RunBehaviorCriteria criteria;private final SequentialCheckpoints checkpoints;
    private final AgentProperties props;private final ObjectMapper mapper;
    private final UnitWaveExecutor waves=new UnitWaveExecutor();
    private UnitOutputCheckpoints unitCheckpoints;
    @org.springframework.beans.factory.annotation.Autowired
    public void useUnitCheckpoints(UnitOutputCheckpoints checkpoints){this.unitCheckpoints=checkpoints;}
    public ContractCoordinator(ContractStore store,ContractPlanner planner,ContractScaffold scaffold,UnitGenerator generator,ApprovedProjectMemory memory,
            BuildDecisions decisions,ProjectFiles files,ChangeSetService changes,ProjectValidationService validation,RunBehaviorCriteria criteria,
            SequentialCheckpoints checkpoints,AgentProperties props,ObjectMapper mapper){
        this.store=store;this.planner=planner;this.scaffold=scaffold;this.generator=generator;this.memory=memory;this.decisions=decisions;this.files=files;
        this.changes=changes;this.validation=validation;this.criteria=criteria;this.checkpoints=checkpoints;this.props=props;this.mapper=mapper;
    }
    public boolean eligible(AgentRun run,RunSize size,String request) {
        if (RequestRouter.route(request) != RequestRouter.Route.CHANGE) {
            return false;
        }
        var approved=memory.current(run.getProjectId());
        var choice=approved==null?null:decisions.state(run.getProjectId()).choice();
        boolean fullStack=props.isFullStackContracts() && choice!=null && choice.storage()==BuildDecisions.Storage.MERN;
        if(!props.isStructuredContracts() && !fullStack && unitCheckpoints!=null && store.forRun(run.getProjectId(),run.getId())!=null)
            throw new WorkflowStageException("This run needs structured execution; restore its reviewed configuration before resuming");
        return (props.isStructuredContracts() || fullStack) && approved!=null &&
                (run.getMode()==ProjectType.PLAN || run.getMode()==ProjectType.BUILD && (fullStack || size.tier()!=SizeTier.S ||
                RequestRouter.buildApprovedPlan(request)));
    }
    public record Outcome(BuildContract contract,String summary) {}
    public Outcome execute(AgentContext ctx,AgentRun run,RunSize size,String request,String projectMap,
                           Function<LlmClient.LlmRequest,LlmClient.LlmResult> checkedCall,Runnable beforeIo,Consumer<String> activity) {
        var allowed=new BuildContract.Limits(size.maxModelCalls(),size.maxToolCalls(),props.getLlm().getMaxOutputTokens(),size.timeoutMs());
        var basis=store.current(ctx.projectId());var approved=memory.current(ctx.projectId());
        var stored=store.forRun(ctx.projectId(),run.getId());
        if(stored==null && run.getMode()==ProjectType.BUILD){var reuse=store.reusable(ctx.projectId(),request,allowed);if(reuse!=null)stored=store.attach(run,reuse,allowed);}
        if(stored==null){activity.accept("Planning implementation");var choice=decisions.state(ctx.projectId()).choice();String root=choice.storage()==BuildDecisions.Storage.MERN?"client/src/App.tsx":choice.storage()==BuildDecisions.Storage.NONE?null:"src/App.tsx";
            String requiredRoot=root!=null && !files.regularFile(ctx.projectId(),ctx.root(),root)?root:null;
            var contract=planner.plan(new ContractPlanner.Input(UUID.randomUUID(),request,approved.requirementsJson(),projectMap,choice,allowed,requiredRoot),props.preferredModel(run.getMode()),checkedCall,proposal->store.validateSources(ctx.projectId(),proposal));stored=store.create(run,contract,basis,allowed);}
        if(!stored.binding().equals(basis) || !stored.contract().limits().within(allowed) || "INVALIDATED".equals(stored.state()))throw new WorkflowStageException("Stored plan no longer matches this run");
        var c=stored.contract();
        if(run.getMode()==ProjectType.PLAN)return new Outcome(c,"Implementation plan:\n"+c.waves().stream().flatMap(List::stream).map(u->"- "+u.objective()+" ("+String.join(", ",u.ownedPaths())+")").collect(java.util.stream.Collectors.joining("\n"))+"\n\nReview and approve this plan before asking to build it.");
        if(!"APPROVED".equals(stored.state()))throw new WorkflowStageException("Review and approve the plan before Build");
        checkpoints.restrict(run,c.limits(),size);
        ctx.plan().revise(c.waves().stream().flatMap(List::stream).map(BuildContract.Unit::objective).toList(),"Approved immutable build contract");
        beforeIo.run();activity.accept("Preparing shared interfaces");
        Map<String,String> common=scaffold.render(c,"App",p->files.regularFile(ctx.projectId(),ctx.root(),p)?bounded(ctx,p):null);
        merge(ctx,common.entrySet().stream().map(e->new UnitGenerator.Delta(e.getKey(),files.version(ctx.projectId(),ctx.root(),e.getKey()),e.getValue())).toList());
        for(var asset:c.assets()){
            beforeIo.run();if(!files.version(ctx.projectId(),ctx.root(),asset.source()).equals(asset.fingerprint()))throw new WorkflowStageException("Asset source changed");
            if(files.version(ctx.projectId(),ctx.root(),asset.destination()).equals(asset.fingerprint()))continue;
            if(files.exists(ctx.projectId(),ctx.root(),asset.destination()))throw new WorkflowStageException("Asset destination needs a reviewed replacement");
            try(var scope=UnitWriteScope.wave(ctx,c,c.units().stream().filter(u->u.id().equals(asset.owner())).toList())){changes.copy(ctx,asset.source(),asset.destination());}
        }
        criteria.install(run.getId(),ctx.projectId(),basis.requirementsProjection(),basis.requirementsRevision(),c.acceptance());
        for(var wave:c.waves()){
            requireBasis(ctx,basis);var outputs=new ArrayList<UnitGenerator.Delta>();
            var inputs=new ArrayList<UnitGenerator.Input>();
            for(var unit:wave){activity.accept(unit.objective());if(c.assets().stream().map(BuildContract.Asset::destination).toList().containsAll(unit.ownedPaths()))continue;var in=input(ctx,c,unit,request,approved.requirementsJson(),common.keySet(),"");var cached=unitCheckpoints==null?null:unitCheckpoints.reuse(run,in,ctx);if(cached==null)inputs.add(in);else outputs.addAll(cached.files());}
            int width=props.isParallelUnits() && inputs.size()>1?2:1;
            if(width==2){var scheduling=props.getScheduling();var model=props.preferredModel(ProjectType.BUILD);var pool=scheduling.getPools().get(model.provider());
                if(!scheduling.isEnabled() || scheduling.getGlobalConcurrency()<2 || scheduling.getUserConcurrency()<2 || scheduling.getRunConcurrency()<2 || pool==null || pool.getConcurrency()<2)throw new WorkflowStageException("Two-worker execution needs verified shared provider/account capacity");}
            var progress=checkpoints.progress(run.getId());if(progress==null)throw new WorkflowStageException("Durable wave deadline unavailable");
            var batching=new BatchUnitGenerator(mapper);
            if(width==1 && props.isBatchGeneration() && !props.isParallelUnits() && batching.eligible(inputs)) {
                var frozen=BatchUnitGenerator.checkpointInputs(inputs);var recovered=new ArrayList<UnitGenerator.Output>();
                if(unitCheckpoints!=null)for(var input:frozen){var cached=unitCheckpoints.reuse(run,input,ctx);if(cached==null){recovered.clear();break;}recovered.add(cached);}
                List<UnitGenerator.Output> generated=recovered;
                if(recovered.size()!=frozen.size()) {
                    try(var scope=UnitWriteScope.worker(ctx)){generated=batching.generate(frozen,props.preferredModel(ProjectType.BUILD),checkedCall);}
                    for(int index=0;index<frozen.size();index++)if(unitCheckpoints!=null)unitCheckpoints.save(run,frozen.get(index),generated.get(index));
                }
                for(var output:generated)outputs.addAll(output.files());
            }else for(var output:waves.generate(inputs,width,progress.remainingMs(),in->{try(var scope=UnitWriteScope.worker(ctx)){var result=generator.generate(in,props.preferredModel(ProjectType.BUILD),checkedCall);if(unitCheckpoints!=null)unitCheckpoints.save(run,in,result);return result;}}))outputs.addAll(output.files());
            beforeIo.run();requireBasis(ctx,basis);try(var scope=UnitWriteScope.wave(ctx,c,wave)){merge(ctx,outputs);}
            // Intermediate compiler checks never certify incomplete acceptance or a pending unit.
            beforeIo.run();var report=validation.run(ctx,ValidationOperation.TYPE_CHECK,null);
            if(report.available() && !report.changeValidationPassed())repair(ctx,run,c,wave,request,approved.requirementsJson(),common.keySet(),report,checkedCall,beforeIo,activity);
            beforeIo.run();report=validation.run(ctx,ValidationOperation.BUILD,null);
            if(report.available() && !report.changeValidationPassed())repair(ctx,run,c,wave,request,approved.requirementsJson(),common.keySet(),report,checkedCall,beforeIo,activity);
        }
        for(int attempt=0;;attempt++){
            beforeIo.run();requireBasis(ctx,basis);activity.accept("Checking required behavior");var report=validation.validateAll(ctx);
            if(report.changeValidationPassed() && c.acceptance().api().stream().anyMatch(step->report.checks().stream().noneMatch(check->check.id().equals("API_"+step.id()) && check.outcome()==ValidationCheck.Outcome.PASS)))
                throw new WorkflowStageException("Required API/account acceptance is unavailable for this connector; staged code was not saved");
            if(report.changeValidationPassed() && report.checks().stream().anyMatch(check->check.id().equals("RUNTIME") && check.outcome()==ValidationCheck.Outcome.PASS))break;
            if(report.changeValidationPassed())throw new WorkflowStageException("Required browser acceptance is unavailable; staged code was not saved");
            if(report.checks().stream().anyMatch(ValidationCheck::unavailable) || changes.validationProgress(ctx).repairLimitExhausted() || attempt>=props.getMaxRepairAttempts())throw new WorkflowStageException("Required application checks did not pass; staged code was not saved");
            var affected=affected(c,report);repair(ctx,run,c,affected,request,approved.requirementsJson(),common.keySet(),report,checkedCall,beforeIo,activity);
        }
        int step=1;for(var unit:c.waves().stream().flatMap(List::stream).toList())ctx.plan().updateStep(step++,AgentPlanState.StepStatus.COMPLETED,"Required backend checks passed");
        if(unitCheckpoints!=null)unitCheckpoints.verified(run);
        return new Outcome(c,"The requested application changes passed the required checks.");
    }
    private void repair(AgentContext ctx,AgentRun run,BuildContract c,List<BuildContract.Unit> units,String request,String req,Set<String> shared,ValidationReport report,
            Function<LlmClient.LlmRequest,LlmClient.LlmResult> checked,Runnable beforeIo,Consumer<String> activity) {
        if(changes.validationProgress(ctx).repairLimitExhausted())throw new WorkflowStageException("Repair limit reached");
        var output=new ArrayList<UnitGenerator.Delta>();String feedback=mapper.writeValueAsString(Map.of("diagnostics",report.introducedDiagnostics(),"checks",report.checks()));
        var repairInputs=units.stream().map(unit->input(ctx,c,unit,request,req,shared,feedback)).toList();
        var batching=new BatchUnitGenerator(mapper);
        if(props.isBatchGeneration() && !props.isParallelUnits() && batching.eligible(repairInputs)) {
            var frozen=BatchUnitGenerator.checkpointInputs(repairInputs);List<UnitGenerator.Output> generated;
            activity.accept("Repairing affected features");
            try(var scope=UnitWriteScope.worker(ctx)){generated=batching.generate(frozen,props.preferredModel(ProjectType.BUILD),checked);}
            for(int index=0;index<frozen.size();index++){if(unitCheckpoints!=null)unitCheckpoints.save(run,frozen.get(index),generated.get(index));output.addAll(generated.get(index).files());}
        }else for(var input:repairInputs){activity.accept("Repairing "+input.unit().objective());try(var scope=UnitWriteScope.worker(ctx)){var result=generator.generate(input,props.preferredModel(ProjectType.BUILD),checked);if(unitCheckpoints!=null)unitCheckpoints.save(run,input,result);output.addAll(result.files());}}
        beforeIo.run();try(var scope=UnitWriteScope.wave(ctx,c,units)){merge(ctx,output);}
    }
    static List<BuildContract.Unit> affected(BuildContract c,ValidationReport report) {
        var ids=new HashSet<String>();
        for(var check:report.checks())if(check.required() && check.outcome()==ValidationCheck.Outcome.FAIL)for(var unit:c.units())if(unit.acceptanceIds().contains(check.id().replaceFirst("^(API_|BROWSER_)","")))ids.add(unit.id());
        for(var diagnostic:report.introducedDiagnostics()){
            if(diagnostic.file()!=null){String owner=c.ownership().get(diagnostic.file());if(owner!=null)ids.add(owner);}
            if("BROWSER_ACCEPTANCE_FAILED".equals(diagnostic.code()))for(var unit:c.units())for(String id:unit.acceptanceIds())if(diagnostic.message().contains("Required interaction "+id+" did not pass"))ids.add(unit.id());
        }
        if(ids.isEmpty())ids.addAll(c.units().stream().map(BuildContract.Unit::id).toList());
        // Shared interfaces are immutable. A dependency edge alone does not prove a dependent needs
        // regeneration; fresh whole-project validation will identify any actual downstream failure.
        return c.waves().stream().flatMap(List::stream).filter(u->ids.contains(u.id())).toList();
    }
    private UnitGenerator.Input input(AgentContext ctx,BuildContract c,BuildContract.Unit unit,String request,String req,Set<String> shared,String feedback){
        var paths=new TreeSet<String>(unit.ownedPaths());paths.addAll(unit.imports());
        c.units().stream().filter(u->unit.dependsOn().contains(u.id())).forEach(u->paths.addAll(u.ownedPaths()));
        paths.addAll(shared.stream().filter(p->p.startsWith(c.clientRoot()) || p.startsWith(c.serverRoot())).filter(p->!p.endsWith("contract.json")).toList());
        var assets=new HashSet<String>();c.assets().forEach(a->assets.add(a.destination()));var snapshot=new TreeMap<String,UnitGenerator.FileView>();
        for(String path:paths){if(assets.contains(path))continue;String version=files.version(ctx.projectId(),ctx.root(),path);snapshot.put(path,new UnitGenerator.FileView(version,files.regularFile(ctx.projectId(),ctx.root(),path)?bounded(ctx,path):null));}
        return new UnitGenerator.Input(c,unit,request,req,snapshot,feedback);
    }
    private String bounded(AgentContext ctx,String path){
        byte[] bytes=files.readBytes(ctx.projectId(),ctx.root(),path,1_000_000);
        try{String text=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\0')>=0)throw new WorkflowStageException("Binary asset needs declared source identity");return text;}
        catch(java.nio.charset.CharacterCodingException binary){throw new WorkflowStageException("Binary asset needs declared source identity");}
    }
    private void merge(AgentContext ctx,List<UnitGenerator.Delta> files){if(!files.isEmpty())changes.replaceBatch(ctx,files.stream().map(f->new ChangeSetService.Replacement(f.path(),f.expectedVersion(),f.content())).toList());}
    private void requireBasis(AgentContext ctx,ContractStore.Binding expected){if(!store.current(ctx.projectId()).equals(expected))throw new WorkflowStageException("Approval/code/capabilities changed; create a current plan");}
}
