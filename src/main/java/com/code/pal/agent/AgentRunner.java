package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.context.ContextDiagnostics;
import com.code.pal.agent.context.ContextMessage;
import com.code.pal.agent.context.ConversationCompactor;
import com.code.pal.agent.context.ProjectContext;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.context.ProjectFileSummary;
import com.code.pal.agent.domain.AgentFailureReason;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import com.code.pal.agent.llm.LlmClient.ToolSpec;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.TokenEstimate;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.agent.route.SizeTierRules;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.runtime.AgentRuntime;
import com.code.pal.agent.runtime.AgentRuntimeContext;
import com.code.pal.agent.runtime.AgentRuntimeStatus;
import com.code.pal.agent.runtime.AgentPhase;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentProjectMetadata;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.usage.AgentUsagePolicy;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.preview.DraftPreviewService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.vision.VisionService;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.database.GeneratedDatabaseService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runs a bounded, recoverable agent turn without holding a database transaction
 * across LLM I/O.
 */
@Slf4j
@Service
public class AgentRunner implements AgentRuntime {

    private static final int HISTORY_CANDIDATE_LIMIT = 100;
    private static final int TOOL_RESULT_MAX = 30_000;
    private static final Set<String> READ_ONLY_FOUNDATION_TOOLS = Set.of(
            "listFiles", "readFile", "searchCode", "getProjectMetadata", "getFileSummary", "getApprovedRequirement", "findProjectFiles");
    private static final Set<String> PLANNING_TOOLS = Set.of("setPlan", "updatePlanStep");
    /** Tools that change staged files (a draft preview follows them). */
    private static final Set<String> FILE_CHANGING_TOOLS = Set.of("createFile", "createFiles", "patchFile", "deleteFile", "renameFile", "copyFile",
            "startProject", "refreshDependencies");
    private static final Set<String> WRITE_TOOLS = Set.of("startProject", "createFile", "createFiles", "patchFile", "deleteFile",
            "renameFile", "copyFile", "refreshDependencies",
            "getDiagnostics", "runTypeCheck", "runBuild", "runTests", "validateChanges", "finishBuild");

    private com.code.pal.agent.lease.ProjectLeaseService leases;
    @Autowired void useLeases(com.code.pal.agent.lease.ProjectLeaseService leases) { this.leases=leases; }
    private <T> T owned(AgentRun run,java.util.function.Supplier<T> action) {
        return leases==null?action.get():leases.guarded(run.getProjectId(),action);
    }
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final AgentToolExecutor toolExecutor;
    private final AgentEvents events;
    private final AgentProperties props;
    private final ProjectContextService projectContexts;
    private final AgentRunRepository runRepository;
    private final ProjectService projects;
    private final MessageRepository messageRepository;
    private final RealtimeService realtime;
    private final ProjectFiles files;
    private final AgentHeartbeat heartbeat;
    private final AgentJobQueue queue;
    private final ObjectMapper objectMapper;
    private final ChangeSetService changeSets;
    private com.code.pal.agent.change.SequentialCheckpoints checkpoints;
    @org.springframework.beans.factory.annotation.Autowired
    void useCheckpoints(com.code.pal.agent.change.SequentialCheckpoints checkpoints) { this.checkpoints=checkpoints; }
    private com.code.pal.agent.contract.ContractCoordinator contractCoordinator;
    @Autowired void useContractCoordinator(com.code.pal.agent.contract.ContractCoordinator coordinator){this.contractCoordinator=coordinator;}
    private final ProjectValidationService validation;
    private final NotificationService notifications;
    private final ProjectArtifactService artifacts;
    private final GeneratedDatabaseService databases;
    /** Builds the served site after a commit; null in focused tests. */
    private final SiteBuildService sites;
    /** Reads attached images; null in focused tests. */
    private final VisionService vision;
    /** Per-user daily token quota; null in focused tests, where no quota applies. */
    private final AgentUsagePolicy usage;
    /** Draft previews of staged changes while a BUILD run works; null in focused tests. */
    private final DraftPreviewService drafts;
    /** Immediate Stop signals for this process's runs; null in focused tests (the queue's flag is checked instead). */
    private final AgentCancellations cancellations;
    private com.code.pal.project.capability.BuildDecisions buildDecisions;
    @Autowired
    public void setBuildDecisions(com.code.pal.project.capability.BuildDecisions decisions) {
        buildDecisions = java.util.Objects.requireNonNull(decisions);
    }

    @Autowired
    public AgentRunner(LlmClient llm, ToolRegistry tools,
            AgentToolExecutor toolExecutor, AgentEvents events, AgentProperties props,
            ProjectContextService projectContexts, AgentRunRepository runRepository,
            ProjectService projects, MessageRepository messageRepository, RealtimeService realtime,
            ProjectFiles files, AgentHeartbeat heartbeat, AgentJobQueue queue,
            ObjectMapper objectMapper, ChangeSetService changeSets,
            ProjectValidationService validation, NotificationService notifications,
            ProjectArtifactService artifacts, GeneratedDatabaseService databases,
            SiteBuildService sites, VisionService vision, AgentUsagePolicy usage, DraftPreviewService drafts,
            AgentCancellations cancellations) {
        this.drafts = drafts;
        this.cancellations = cancellations;
        this.sites = sites;
        this.vision = vision;
        this.usage = usage;
        this.llm = llm;
        this.tools = tools;
        this.toolExecutor = toolExecutor;
        this.events = events;
        this.props = props;
        this.projectContexts = projectContexts;
        this.runRepository = runRepository;
        this.projects = projects;
        this.messageRepository = messageRepository;
        this.realtime = realtime;
        this.files = files;
        this.heartbeat = heartbeat;
        this.queue = queue;
        this.objectMapper = objectMapper;
        this.changeSets = changeSets;
        this.validation = validation;
        this.notifications = notifications;
        this.artifacts = artifacts;
        this.databases = databases;
    }

    /**
     * Keeps focused runner tests and small embedders source-compatible as workflow
     * sinks evolve.
     */
    public AgentRunner(LlmClient llm, ToolRegistry tools,
            AgentToolExecutor toolExecutor, AgentEvents events, AgentProperties props,
            ProjectContextService projectContexts, AgentRunRepository runRepository,
            ProjectService projects, MessageRepository messageRepository, RealtimeService realtime,
            ProjectFiles files, AgentHeartbeat heartbeat, AgentJobQueue queue,
            ObjectMapper objectMapper, ChangeSetService changeSets,
            ProjectValidationService validation, NotificationService notifications) {
        this(llm, tools, toolExecutor, events, props, projectContexts, runRepository, projects,
                messageRepository, realtime, files, heartbeat, queue, objectMapper, changeSets, validation,
                notifications, null, null, null, null, null, null, null);
    }

    /**
     * Execute a read-only run end to end; every terminal outcome is persisted and
     * returned.
     */
    @Override
    public AgentRunResult run(AgentRun run) {
        DeltaBatcher stream = new DeltaBatcher(run);
        AutoCloseable beat = null;
        AgentRuntimeContext runtime = null;
        ProjectContext projectContext = null;
        AgentContext ctx = null;
        // The run's Stop signal, bound to this thread so model calls and sandbox commands made for the run
        // stop the moment it is cancelled (not only between iterations).
        RunCancellation cancellation = cancellations == null ? new RunCancellation(run.getId()) : cancellations.register(run.getId());
        RunCancellation.Registration boundCancellation = RunCancellation.bind(cancellation);
        // Who the run's model calls are for, for the call ledger; the loop's own calls are counted in the run's totals.
        LlmCallContext.Scope callContext = LlmCallContext.bind(new LlmCallContext(run.getUserId(), run.getProjectId(), run.getId(), CallPurpose.AGENT, true));
        try {
            long startedNanos = System.nanoTime();
            // Today's limit until the run's size is known; M and L builds get the longer one below.
            long deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(props.getRunTimeoutMs());
            run.setHeartbeatAt(Instant.now());
            setStatus(run, RunStatus.RUNNING);
            log.info("agent_started runId={} projectId={} userId={}", run.getId(), run.getProjectId(), run.getUserId());
            events.emit(run, AgentStepType.STATUS, "Agent started", Map.of("status", "RUNNING"));
            beat = heartbeat.start(run.getId(), run.getProjectId());
            updateAssistant(run, "", MessageStatus.STREAMING);
            requireNotCancelled(run); // cancelled while still queued — stop before any work
            // Admission checked the quota when the turn was posted; a long run must also stop once it
            // crosses it; every checkpoint reads the current durable UTC accounting projection.
            requireWithinDailyQuota(run);

            // All accepted agent turns require current edit rights, including conversational builds.
            Project project = projects.requireCanEdit(run.getUserId(), run.getProjectId());

            RunRequestHistory accepted = RunRequestHistory.load(messageRepository, run, HISTORY_CANDIDATE_LIMIT);
            List<Message> history = accepted.messages();
            String userRequest = accepted.request().getResolvedRequestText() == null
                    ? accepted.request().getContent() : accepted.request().getResolvedRequestText();
            List<String> attachments = accepted.request().executionAttachmentPaths();
            RequestRouter.Route route = RequestRouter.route(userRequest, attachments, false);
            ProjectType effectiveMode = RequestRouter.mode(run.getMode(), userRequest, attachments);
            if (effectiveMode != run.getMode()) {
                run.setMode(effectiveMode);
                owned(run, () -> runRepository.save(run));
            }
            if (route == RequestRouter.Route.UNCLEAR
                    || route == RequestRouter.Route.CHANGE && run.getMode() != ProjectType.BUILD) {
                suggestBuild(run, userRequest, attachments);
            }
            if (route == RequestRouter.Route.SMALL_TALK
                    || route == RequestRouter.Route.UNCLEAR && attachments.isEmpty()) {
                return runConversational(run, stream, history, userRequest, deadlineNanos);
            }

            if (run.getMode() == ProjectType.BUILD && buildDecisions != null) {
                var decision = buildDecisions.state(run.getProjectId());
                if (decision.blocked()) throw new com.code.pal.project.exception.WorkflowStageException(decision.problem());
            }
            if (run.getMode() == ProjectType.BUILD)
                moveProject(run, ProjectStage.BUILDING);
            // BUILD runs work on a staged copy committed at the end; other runs read the project itself.
            String root = run.getMode() == ProjectType.BUILD ? changeSets.start(run.getId(), run.getProjectId()) : ".";
            projectContext = projectContexts.build(files, run.getProjectId(), root, userRequest, history, List.of());
            RunSize size = runSize(run, project, userRequest, projectContext);
            if (usage != null) usage.configureRun(run.getId(),run.getUserId(),size.tier());
            deadlineNanos = startedNanos + TimeUnit.MILLISECONDS.toNanos(size.timeoutMs());
            events.emit(run, AgentStepType.STATUS, "Run size", Map.of("tier", size.tier().name(), "reasons", size.reasons(),
                    "timeoutMs", size.timeoutMs(), "maxIterations", size.maxIterations()));
            com.code.pal.agent.change.SequentialCheckpoints.Progress durableProgress=null;
            if(checkpoints!=null) {
                durableProgress=checkpoints.configure(run,size);
                deadlineNanos=Math.min(deadlineNanos,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(durableProgress.remainingMs()));
            }
            AgentPlanState plan = new AgentPlanState();
            ctx = new AgentContext(run.getProjectId(), run.getUserId(), run.getMode(), root,
                    AgentProjectMetadata.from(project), projectContext.trace(), run.getId(), plan, files);
            ctx.contextTrace().patchMissLimit(props.getMaxPatchMisses());
            String resumed=run.getMode()==ProjectType.BUILD?changeSets.resumePlan(ctx):null;
            List<AgentTool> available = toolsForRun(run.getMode());
            List<ToolSpec> specs = available.stream()
                    .map(t -> new ToolSpec(t.name(), t.description(), t.parametersSchema())).toList();
            int specChars = specs.toString().length();
            ConversationCompactor compactor = props.isCompactContext()
                    ? new ConversationCompactor(objectMapper, props.getCompactKeepTurns(), props.getCompactMinChars()) : null;

            List<LlmMessage> convo = new ArrayList<>();
            convo.add(LlmMessage.system(systemPrompt(run.getMode())));
            if (!projectContext.projectContextMessage().isBlank()) {
                convo.add(LlmMessage.user(projectContext.projectContextMessage()));
            }
            projectContext.conversation().stream().map(AgentRunner::toLlmMessage).forEach(convo::add);
            if (accepted.request().getResolvedRequestText() != null) {
                convo.add(LlmMessage.user(userRequest));
            }
            if (!attachments.isEmpty() && vision != null) {
                events.emit(run, AgentStepType.STATUS, "Reading attachments", Map.of("files", attachments));
                convo.add(LlmMessage.user(VisionService.asContext(vision.describe(run.getProjectId(), attachments, userRequest))));
            }
            if(resumed!=null) convo.add(LlmMessage.user(resumed));
            int initialContextChars = convo.stream()
                    .mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum()
                    + specs.toString().length();
            runtime = new AgentRuntimeContext(run.getId(), run.getProjectId(), run.getUserId(), userRequest, convo,
                    props.getContextBudgetChars(), initialContextChars, plan);
            if(durableProgress!=null) runtime.restoreCounters(durableProgress.iterations(),durableProgress.modelCalls(),durableProgress.toolCalls());
            runtime.transitionTo(AgentRuntimeStatus.RUNNING);
            runtime.transitionPhase(AgentPhase.EXPLORE);
            events.emit(run, AgentStepType.STATUS, "Project context prepared",
                    contextAudit(projectContext.diagnostics()));

            if(attachments.isEmpty() && contractCoordinator!=null && contractCoordinator.eligible(run,size,userRequest)) {
                if(checkpoints==null)throw new AgentLimitExceededException("Durable structured execution is unavailable");
                final AgentRuntimeContext structuredRuntime=runtime;final long structuredDeadline=deadlineNanos;
                java.util.function.Function<LlmRequest,LlmResult> checkedCall=request->{
                    long currentDeadline=Math.min(structuredDeadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(checkpoints.progress(run.getId()).remainingMs()));
                    requireBeforeDeadline(currentDeadline);requireNotCancelled(run);requireWithinDailyQuota(run);
                    if(request.messages().stream().mapToInt(m->m.content()==null?0:m.content().length()).sum()>props.getContextBudgetChars())throw new AgentLimitExceededException("Structured context limit reached");
                    synchronized(structuredRuntime){requireModelCallAvailable(structuredRuntime,size);
                        if(!checkpoints.reserveModelIteration(run))throw new AgentLimitExceededException("Durable model/iteration/deadline limit reached");
                        structuredRuntime.beginIteration();structuredRuntime.recordModelCall();}
                    var budget=callBudget(currentDeadline);if(request.budget()!=null && request.budget().compareTo(budget)<0)budget=request.budget();
                    var bounded=new LlmRequest(request.provider(),request.model(),request.messages(),request.tools(),request.temperature(),budget,request.toolChoice(),request.maxTokens(),request.jsonSchema());
                    var response=llm.chat(bounded);synchronized(run){accountTokens(run,response);}return response;
                };
                Runnable beforeIo=()->{
                    requireNotCancelled(run);requireBeforeDeadline(structuredDeadline);requireToolCallsAvailable(structuredRuntime,1,size);
                    if(!checkpoints.reserveTools(run,1))throw new AgentLimitExceededException("Durable operation/deadline limit reached");structuredRuntime.recordToolCalls(1);
                };
                var outcome=contractCoordinator.execute(ctx,run,size,userRequest,projectContext.projectContextMessage(),checkedCall,beforeIo,
                        title->events.emit(run,AgentStepType.STATUS,title,null));
                if(run.getMode()==ProjectType.BUILD){
                    beforeIo.run();projects.requireCanEdit(run.getUserId(),run.getProjectId());
                    if(!hasPreviewEntrypoint(ctx))throw new AgentValidationFailedException("The structured build has no preview entrypoint");
                    var committed=changeSets.commit(ctx,run);
                    if(committed.changedPaths().isEmpty())throw new AgentValidationFailedException("The structured build produced no changes");
                    events.emit(run,AgentStepType.STATUS,"Change set committed",Map.of("changedFiles",committed.changedPaths(),"snapshotRef",committed.snapshotRef()));
                    if(artifacts!=null)artifacts.writeBuild(run,"COMMITTED",committed.changedPaths());
                    if(databases!=null)databases.ensure(run.getProjectId());buildSite(run);moveProject(run,ProjectStage.REVIEW);
                    stream.append(BuildReplyText.success(outcome.summary(),run));
                }else stream.append(outcome.summary());
                runtime.transitionPhase(AgentPhase.FINISH);runtime.transitionTo(AgentRuntimeStatus.COMPLETED);setStatus(run,RunStatus.DONE);stream.complete();notifyBuild(run,true);
                events.emit(run,AgentStepType.STATUS,"Agent completed",counters(runtime));emitContextUsage(run,projectContext,runtime);
                return result(run,runtime,stream.content(),null);
            }

            int planningReminders = 0;
            int implementationReminders = 0;
            int previewReminders = 0;
            // One review turn per build, once the change validates: weak models stop at the first
            // version that runs, and the checklist brings back what they skipped.
            boolean polished = !props.isPolishTurn();
            if(run.getMode()==ProjectType.BUILD && props.isAutomaticStarter() && buildDecisions!=null) {
                var starter=com.code.pal.agent.starter.AutomaticStarter.select(ctx,buildDecisions.state(run.getProjectId()).choice(),userRequest);
                if(starter!=null && available.stream().anyMatch(tool->tool.name().equals("startProject"))) {
                    requireBeforeDeadline(deadlineNanos);requireNotCancelled(run);requireToolCallsAvailable(runtime,1,size);
                    if(checkpoints!=null && !checkpoints.reserveTools(run,1))throw new AgentLimitExceededException("Durable preparation limit reached");
                    if(!ctx.plan().exists())ctx.plan().revise(List.of("Prepare the approved application setup","Implement the requested features","Validate the application"),"Backend starter preparation");
                    runtime.recordToolCalls(1);
                    var setup=executeTools(available,List.of(new ToolCall("backend_prepare","startProject",objectMapper.writeValueAsString(Map.of("stack",starter.id(),"title",project.getName()==null?"App":project.getName())))),ctx,run,runtime,deadlineNanos);
                    if(!setup.allSucceeded())throw new AgentValidationFailedException("Approved starter preparation failed; no code was saved");
                    String prepared=objectMapper.writeValueAsString(com.code.pal.agent.starter.AutomaticStarter.context(ctx,starter));
                    String data="Backend prepared the approved starter in this stage. Keep its setup; do not call startProject again. Implement the request on top. Starter data: "+prepared;
                    if(!runtime.reserveContext(data.length()))throw new AgentLimitExceededException("Prepared starter exceeds context capacity");
                    runtime.messages().add(LlmMessage.user(data));
                }
            }
            // The answer from before the review, and the revision it described: when the review changes
            // nothing, its reply is talk about the review, not an answer for the user.
            String firstSummary = null;
            int reviewedRevision = -1;
            // One model for the whole run (the first available in priority order); another one only for
            // the calls made while it is failing, and back to it once its cooldown is over.
            AgentProperties.ModelSelection preferred = props.preferredModel(run.getMode());
            var failedModels = new java.util.HashSet<AgentProperties.ModelSelection>();
            LlmException lastProviderFailure = null;
            for (int i = runtime.iteration(); i < size.maxIterations(); i++) {
                requireBeforeDeadline(deadlineNanos);
                requireNotCancelled(run);
                // Terminate known failure before reserving/calling a model whose reply would be discarded.
                if (run.getMode() == ProjectType.BUILD && changeSets.validationProgress(ctx).repairLimitExhausted())
                    throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
                if (ctx.contextTrace().patchLimitReached()) throw new AgentValidationFailedException(
                        "Repeated patch misses; staged changes were not committed. Re-read and revise the approach for: " + ctx.contextTrace().patchBlockedPaths());
                if (compactor != null) {
                    ConversationCompactor.Saved saved=compactor.compact(runtime.messages());
                    saved.omittedReadPaths().forEach(ctx.contextTrace()::forgetRead);
                    if(saved.total()>0) runtime.recordCompaction(saved.resultChars(),saved.argumentChars());
                }
                long actualChars=specChars;
                for(var message:runtime.messages()) {
                    actualChars+=message.content()==null?0:message.content().length();
                    if(message.toolCalls()!=null)for(var call:message.toolCalls()) {
                        actualChars+=call.arguments()==null?0:call.arguments().length();
                        if(call.extraContent()!=null)actualChars+=objectMapper.writeValueAsString(call.extraContent()).length();
                    }
                }
                if(actualChars>props.getContextBudgetChars())throw new AgentLimitExceededException("Context including tool arguments exceeds configured capacity; no further model call was sent");
                AgentProperties.ModelSelection selection = props.available(preferred) && !failedModels.contains(preferred) ? preferred : props.untriedModel(run.getMode(),failedModels);
                if(selection==null)throw lastProviderFailure;
                if(checkpoints!=null && !checkpoints.reserveModelIteration(run)) throw new AgentLimitExceededException("Durable model/iteration/deadline limit reached");
                runtime.beginIteration();
                requireModelCallAvailable(runtime, size);
                requireWithinDailyQuota(run);
                runtime.transitionTo(AgentRuntimeStatus.WAITING_FOR_MODEL);
                runtime.recordModelCall();
                log.info("model_called runId={} iteration={} modelCall={} provider={} model={}", run.getId(),
                        runtime.iteration(),
                        runtime.modelCallCount(), selection.provider(), selection.model());
                events.emit(run, AgentStepType.STATUS, "Model called", Map.of(
                        "iteration", runtime.iteration(), "modelCall", runtime.modelCallCount()));
                LlmResult res;
                try {
                    // A build's reply is its final summary: what the model says between tool calls is
                    // narration for the timeline, not part of the answer.
                    // A build cannot finish before it has a plan and has changed files, so until then the
                    // model must call a tool (weak models otherwise answer in prose and stop).
                    boolean mustAct = run.getMode() == ProjectType.BUILD
                            && (!ctx.plan().exists() || changeSets.view(ctx).touchedPaths().isEmpty());
                    res = llm.chatStreaming(new LlmRequest(
                            selection.provider(), selection.model(), runtime.messages(), specs,
                            props.getLlm().getTemperature(), callBudget(deadlineNanos), mustAct ? "required" : null,
                            props.getLlm().getMaxOutputTokens()),
                            run.getMode() == ProjectType.BUILD ? delta -> { } : stream::append);
                } catch (LlmException providerFailure) {
                    // A stopped run is not a provider failure: no fallback, no cooldown.
                    if (providerFailure instanceof LlmCancelledException) throw new AgentCancelledException();
                    if (providerFailure instanceof LlmQuotaExceededException) throw providerFailure;
                    if (providerFailure instanceof LlmAccountingException) throw providerFailure;
                    if(providerFailure instanceof com.code.pal.agent.llm.LlmHttpException rejected && rejected.terminalRequest())throw providerFailure;
                    requireNotCancelled(run);
                    // Keep the conversation intact when a provider rejects a request before it
                    // publishes output. The next round-robin model can support a different tool
                    // schema or have independent rate limits; the model-call cap still bounds cost.
                    if (isPartialOutputFailure(providerFailure)) throw providerFailure;
                    markUnavailable(selection, providerFailure);
                    failedModels.add(selection); lastProviderFailure = providerFailure;
                    log.warn(
                            "llm_provider_unavailable runId={} provider={} model={} reason={}; trying next configured model",
                            run.getId(), selection.provider(), selection.model(), providerFailure.getMessage());
                    events.emit(run, AgentStepType.STATUS, "LLM provider unavailable", Map.of(
                            "provider", selection.provider(), "model", selection.model(),
                            "retryable", providerFailure instanceof com.code.pal.agent.llm.RetryableLlmException));
                    continue;
                }
                props.markModelAvailable(selection);
                stream.flush();
                accountTokens(run, res);

                boolean hasReasoning = res.reasoning() != null && !res.reasoning().isBlank();
                // In a build every message is kept for the timeline: with tools it is narration, without
                // them it is either the final answer or a reply a gate will send back.
                boolean narrated = res.content() != null && !res.content().isBlank()
                        && (res.hasToolCalls() || run.getMode() == ProjectType.BUILD);
                if (hasReasoning || narrated) {
                    Map<String, Object> thinking = new java.util.HashMap<>();
                    if (hasReasoning) thinking.put("reasoning", res.reasoning());
                    if (narrated) thinking.put("narration", truncateNarration(res.content()));
                    thinking.put("toolCallCount", res.toolCalls().size());
                    events.emit(run, AgentStepType.THINKING, "Model reasoning", thinking);
                }

                boolean completionRequested=false;
                if(res.hasToolCalls()) {
                    requireToolCallsAvailable(runtime,res.toolCalls().size(),size);
                    if(checkpoints!=null && !checkpoints.reserveTools(run,res.toolCalls().size())) throw new AgentLimitExceededException("Durable tool/deadline limit reached");
                    runtime.recordToolCalls(res.toolCalls().size());
                    runtime.messages().add(new LlmMessage("assistant",budgetAssistantContext(runtime,res.content()),res.toolCalls(),null));
                    runtime.transitionTo(AgentRuntimeStatus.EXECUTING_TOOL);
                    var executed=executeTools(available,res.toolCalls(),ctx,run,runtime,deadlineNanos);
                    for(int index=0;index<res.toolCalls().size();index++) {
                        String toolResult=budgetToolResult(runtime,executed.results().get(index));var call=res.toolCalls().get(index);
                        if("readFile".equals(call.name()) && objectMapper.readTree(toolResult).path("bodyOmitted").asBoolean(false)) {
                            var path=objectMapper.readTree(call.arguments()).path("path");
                            if(path.isString())ctx.contextTrace().forgetRead(ctx.resolvePath(path.asString()));
                        }
                        runtime.messages().add(LlmMessage.toolResult(call.id(),toolResult));
                    }
                    completionRequested=run.getMode()==ProjectType.BUILD && executed.allSucceeded()
                            && "finishBuild".equals(res.toolCalls().getLast().name());
                    if(!completionRequested)continue;
                }
                if (!res.hasToolCalls() || completionRequested) {
                    if (run.getMode() == ProjectType.BUILD) {
                        if (!ctx.plan().exists()) {
                            if (planningReminders++ > 0) {
                                throw new AgentValidationFailedException(
                                        "The agent did not create an implementation plan before finishing the build");
                            }
                            runtime.messages().add(LlmMessage.user(
                                    "BUILD GATE: The build cannot finish yet because no plan was created. "
                                            + "Call setPlan with concrete file-oriented steps now, then implement the request with the file tools."));
                            continue;
                        }
                        if (changeSets.view(ctx).touchedPaths().isEmpty()) {
                            if (implementationReminders++ > 0) {
                                throw new AgentValidationFailedException(
                                        "The agent did not create or modify any project files");
                            }
                            runtime.messages().add(LlmMessage.user(
                                    "BUILD GATE: The plan exists but no project files were changed. "
                                            + "Use createFile or patchFile to implement the approved request, then validate the staged revision."));
                            continue;
                        }
                        if (changeSets.hasUnvalidatedChanges(ctx)) {
                            // The final gate is deterministic. A model does not need an extra turn
                            // just to call validation after its last mutation.
                            ValidationReport finalReport = validation.validateAll(ctx);
                            events.emit(run, AgentStepType.STATUS, "Final validation", Map.of(
                                    "validationOutcome",finalReport.outcome().name(),
                                    "validationPassed", finalReport.changeValidationPassed(),
                                    "introducedDiagnosticCount", finalReport.introducedDiagnostics().size(),
                                    "repairAttempts", finalReport.repairAttempts()));
                            if (!finalReport.changeValidationPassed()) {
                                if (changeSets.validationProgress(ctx).repairLimitExhausted()) {
                                    throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
                                }
                                runtime.messages().add(LlmMessage.user(finalValidationRepairPrompt(finalReport)));
                                continue;
                            }
                        }
                        if (!hasPreviewEntrypoint(ctx)) {
                            if (previewReminders++ > 0) {
                                throw new AgentValidationFailedException(
                                        "The build has no browser preview entrypoint; no changes were committed");
                            }
                            runtime.messages().add(LlmMessage.user(
                                    "BUILD GATE: The project is not previewable yet. Create a functional frontend entrypoint "
                                            + "at index.html, client/index.html, client/public/index.html, frontend/index.html, "
                                            + "or a supported dist/build index.html, then finish the build."));
                            continue;
                        }
                        if (!polished) {
                            polished = true;
                            firstSummary = res.content();
                            reviewedRevision = changeSets.view(ctx).revision();
                            runtime.messages().add(LlmMessage.assistant(res.content() == null || res.content().isBlank()
                                    ? "The first version is built and validated." : res.content()));
                            runtime.messages().add(LlmMessage.user(polishPrompt(userRequest)));
                            events.emit(run, AgentStepType.STATUS, "Polish check", null);
                            continue;
                        }
                        // Before the commit: committing closes the change set, and with it its revision.
                        String answer = reviewedRevision >= 0 && changeSets.view(ctx).revision() == reviewedRevision
                                ? firstSummary : res.content();
                        String summary = completionRequested ? objectMapper.readTree(res.toolCalls().getLast().arguments()).path("summary").asString("") : answer;
                        events.emit(run, AgentStepType.STATUS, "Saving changes", null);
                        ChangeSetService.CommitResult committed = changeSets.commit(ctx, run);
                        if (committed.changedPaths().isEmpty()) {
                            throw new AgentValidationFailedException(
                                    "The staged build produced no committed file changes");
                        }
                        events.emit(run, AgentStepType.STATUS, "Change set committed", Map.of(
                                "changedFiles", committed.changedPaths(), "snapshotRef", committed.snapshotRef()));
                        if (artifacts != null)
                            artifacts.writeBuild(run, "COMMITTED", committed.changedPaths());
                        if (databases != null)
                            databases.ensure(run.getProjectId());
                        buildSite(run);
                        moveProject(run, ProjectStage.REVIEW);
                        stream.append(BuildReplyText.success(summary,run));
                    }
                    runtime.transitionPhase(AgentPhase.FINISH);
                    runtime.transitionTo(AgentRuntimeStatus.COMPLETED);
                    // Run DONE before the reply shows as complete: a user who answers the moment it
                    // appears must not be told a request is still in progress.
                    setStatus(run, RunStatus.DONE);
                    stream.complete();
                    notifyBuild(run, true);
                    events.emit(run, AgentStepType.STATUS, "Agent completed", counters(runtime));
                    emitContextUsage(run, projectContext, runtime);
                    log.info("agent_completed runId={} iterations={} modelCalls={} toolCalls={}", run.getId(),
                            runtime.iteration(), runtime.modelCallCount(), runtime.toolCallCount());
                    return result(run, runtime, stream.content(), null);
                }

            }

            throw new AgentLimitExceededException("Agent iteration limit reached");
        } catch (com.code.pal.agent.change.PromotionRecoveryPendingException pending) {
            events.emit(run,AgentStepType.STATUS,"Save recovery pending",null);
            try{runsFlagRecovery(run);}catch(RuntimeException unavailable) {log.warn("save_recovery_status_deferred runId={}",run.getId());}
            cancellation.cancel();run.setError(pending.getMessage());
            return result(run,runtime,"Code save needs recovery verification; project writes are paused.",run.getError());
        } catch (com.code.pal.agent.lease.TrustedOperationPendingException pending) {
            cancellation.cancel();run.setError(pending.getMessage());
            return result(run,runtime,"Project operation cleanup is pending.",run.getError());
        } catch (LeaseLostException lost) {
            // Replacement/recovery owns durable state. A stale worker emits no terminal writes.
            return ownershipLost(run, runtime, cancellation);
        } catch (AgentCancelledException | LlmCancelledException e) {
            if (cancellation.isOwnershipLost()) {
                return ownershipLost(run, runtime, cancellation);
            }
            log.info("agent_cancelled runId={}", run.getId());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.CANCELLED);
            run.setError("Cancelled by user");
            setStatus(run, RunStatus.CANCELLED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "CANCELLED", List.of());
            updateAssistant(run, "Run cancelled.", MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, "Agent cancelled", safeCounters(runtime));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, "Run cancelled.", run.getError());
        } catch (AgentQuotaExceededException | LlmQuotaExceededException e) {
            log.info("agent_quota_exceeded runId={} userId={}", run.getId(), run.getUserId());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.FAILED);
            String message = run.getMode() == ProjectType.BUILD
                    ? e.getMessage() + " The build was stopped before any change was committed."
                    : e.getMessage();
            run.setError(message);
            boolean runBudget=e instanceof LlmQuotaExceededException quota && quota.runBudget();
            run.setFailureReason(runBudget?AgentFailureReason.RUN_TOKEN_BUDGET_EXCEEDED:AgentFailureReason.DAILY_QUOTA_EXCEEDED);
            setStatus(run, RunStatus.FAILED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "FAILED", List.of());
            updateAssistant(run, message, MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, runBudget?"Agent stopped: run budget":"Agent stopped: daily quota", Map.of(
                    "status", "FAILED", "reason", runBudget?"RUN_TOKEN_BUDGET_EXCEEDED":"DAILY_QUOTA_EXCEEDED", "message", message));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, message, message);
        } catch (AgentValidationFailedException e) {
            log.info("agent_validation_failed runId={}", run.getId());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.FAILED);
            run.setError(e.getMessage());
            run.setFailureReason(AgentFailureReason.BUILD_VALIDATION_FAILED);
            setStatus(run, RunStatus.FAILED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "FAILED", List.of());
            notifyBuild(run, false);
            String validationMessage="I couldn't complete this build. No new changes were saved. Open Details to review the checks.";
            updateAssistant(run, validationMessage, MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, "Agent validation failed", Map.of(
                    "status", "FAILED", "reason", "BUILD_GATE_FAILED", "message", e.getMessage()));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, validationMessage, e.getMessage());
        } catch (Exception e) {
            log.warn("agent_failed runId={} errorType={} message={}", run.getId(), e.getClass().getSimpleName(), e.getMessage());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.FAILED);
            run.setError(safeError(e));
            run.setFailureReason(e instanceof com.code.pal.agent.llm.LlmHttpException rejected && rejected.terminalRequest()?AgentFailureReason.PROVIDER_REQUEST_REJECTED:
                    e instanceof AgentLimitExceededException?AgentFailureReason.RUN_LIMIT_REACHED:
                    e instanceof com.code.pal.project.exception.WorkflowStageException?AgentFailureReason.USER_REVIEW_REQUIRED:AgentFailureReason.AGENT_FAILED);
            setStatus(run, RunStatus.FAILED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "FAILED", List.of());
            notifyBuild(run, false);
            String failureMessage=run.getCodeSavedAt()==null ? (e instanceof com.code.pal.project.exception.WorkflowStageException?safeError(e):"Something went wrong while running the agent.")
                    : "Your code changes were saved. Preview preparation failed; the saved code is available.";
            if(run.getCodeSavedAt()!=null) recordPreview(run,"FAILED");
            updateAssistant(run, failureMessage, MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, "Agent failed", safeCounters(runtime));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, failureMessage, run.getError());
        } finally {
            callContext.close();
            boundCancellation.close();
            if (cancellations != null) cancellations.unregister(run.getId());
            if (drafts != null) drafts.finish(run.getId());
            changeSets.finish(run);
            validation.clear(run.getId());
            stopQuietly(beat);
            try {
                queue.clearCancel(run.getId());
            } catch (RuntimeException unavailable) {
                log.warn("Redis cancel cleanup deferred runId={} errorType={}",
                        run.getId(), unavailable.getClass().getSimpleName());
            }
        }
    }

    private void runsFlagRecovery(AgentRun run) {runRepository.flagSaveRecovery(run.getId());run.setSaveRecoveryRequired(true);}

    private static final int CONVERSATIONAL_HISTORY_TURNS = 6;
    /** Chat models tried for one small-talk reply before it fails. */
    private static final int CONVERSATIONAL_MODEL_ATTEMPTS = 3;

    /**
     * Direct reply for a conversational turn (a greeting, thanks, etc.), skipping
     * project-context
     * assembly and the tool loop entirely so small talk answers instantly instead
     * of paying for a
     * full explore/plan/implement pass it never needed.
     */
    private AgentRunResult runConversational(AgentRun run, DeltaBatcher stream, List<Message> history,
            String userRequest, long deadlineNanos) {
        projects.requireAccessible(run.getUserId(), run.getProjectId());
        List<LlmMessage> convo = new ArrayList<>();
        convo.add(LlmMessage.system(PromptLoader.load(Prompt.CONVERSATIONAL)));
        List<Message> contextHistory = history.stream()
                .filter(message -> message.getRole() != com.code.pal.message.domain.MessageRole.SYSTEM)
                .toList();
        int lastUserIdx = -1;
        for (int i = contextHistory.size() - 1; i >= 0; i--) {
            if (contextHistory.get(i).getRole() == com.code.pal.message.domain.MessageRole.USER) {
                lastUserIdx = i;
                break;
            }
        }
        List<Message> withoutCurrent = lastUserIdx >= 0
                ? contextHistory.subList(0, lastUserIdx)
                : contextHistory;
        withoutCurrent.stream()
                .skip(Math.max(0, withoutCurrent.size() - CONVERSATIONAL_HISTORY_TURNS))
                .map(message -> message.getRole() == com.code.pal.message.domain.MessageRole.ASSISTANT
                        ? LlmMessage.assistant(message.getContent())
                        : LlmMessage.user(message.getContent()))
                .forEach(convo::add);
        convo.add(LlmMessage.user(userRequest));

        LlmResult res = null;
        LlmException lastFailure = null;
        var failedModels = new java.util.HashSet<AgentProperties.ModelSelection>();
        try (LlmCallContext.Scope smallTalk = LlmCallContext.purpose(CallPurpose.SMALL_TALK, true)) {
            // Small talk gets the same fallback as the main loop: a provider that is down or out of
            // budget hands over to the next configured chat model instead of failing the reply.
            for (int attempt = 1; attempt <= CONVERSATIONAL_MODEL_ATTEMPTS && res == null; attempt++) {
                requireBeforeDeadline(deadlineNanos);
                requireNotCancelled(run);
                requireWithinDailyQuota(run);
                AgentProperties.ModelSelection selection = props.preferredModel(ProjectType.CHAT);
                if (failedModels.contains(selection) || !props.available(selection)) {
                    selection = props.untriedModel(ProjectType.CHAT, failedModels);
                }
                if (selection == null) throw lastFailure;
                log.info("model_called runId={} conversational=true attempt={} provider={} model={}", run.getId(), attempt,
                        selection.provider(), selection.model());
                try {
                    res = llm.chatStreaming(new LlmRequest(selection.provider(), selection.model(), convo, List.of(),
                            props.getLlm().getTemperature(), callBudget(deadlineNanos))
                            .withMaxTokens(props.getLlm().getSmallTalkMaxOutputTokens()), stream::append);
                    props.markModelAvailable(selection);
                } catch (LlmException providerFailure) {
                    if (providerFailure instanceof LlmCancelledException) throw new AgentCancelledException();
                    if (providerFailure instanceof LlmQuotaExceededException) throw providerFailure;
                    if (providerFailure instanceof LlmAccountingException) throw providerFailure;
                    if(providerFailure instanceof com.code.pal.agent.llm.LlmHttpException rejected && rejected.terminalRequest())throw providerFailure;
                    requireNotCancelled(run);
                    if (isPartialOutputFailure(providerFailure)) throw providerFailure;
                    markUnavailable(selection, providerFailure);
                    failedModels.add(selection);
                    lastFailure = providerFailure;
                    log.warn("llm_provider_unavailable runId={} conversational=true provider={} model={} reason={}; trying next configured model",
                            run.getId(), selection.provider(), selection.model(), providerFailure.getMessage());
                }
            }
            if (res == null) throw lastFailure;
            stream.flush();
            accountTokens(run, res);
        }
        if (res.reasoning() != null && !res.reasoning().isBlank()) {
            events.emit(run, AgentStepType.THINKING, "Model reasoning", Map.of("reasoning", res.reasoning()));
        }
        run.setConversational(true);
        setStatus(run, RunStatus.DONE);   // before the reply completes; see the main loop
        stream.complete();
        log.info("agent_completed runId={} conversational=true", run.getId());
        events.emit(run, AgentStepType.STATUS, "Agent completed",
                Map.of("iterations", 1, "modelCalls", 1, "toolCalls", 0, "phase", "CONVERSATION"));
        return new AgentRunResult(run.getId(), run.getStatus(), stream.content(), null, 1, 1, 0);
    }

    /** Stops the run at a checkpoint when Stop was requested (local signal first, the queue's flag as a fallback). */
    private void requireNotCancelled(AgentRun run) {
        if (RunCancellation.current().isOwnershipLost()) {
            throw new LeaseLostException();
        }
        if(leases!=null) leases.checkCurrent(run.getProjectId());
        boolean requested;
        try {
            requested = queue.isCancelRequested(run.getId());
        } catch (RuntimeException unavailable) {
            requested = runRepository.findById(run.getId()).map(current -> current.getCancelRequestedAt() != null)
                    .orElse(false);
        }
        if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted() || requested)
            throw new AgentCancelledException();
    }

    private AgentRunResult ownershipLost(AgentRun run, AgentRuntimeContext runtime, RunCancellation cancellation) {
        cancellation.loseOwnership();
        run.setError("Worker ownership expired or changed");
        run.setStatus(RunStatus.FAILED);
        return result(run, runtime, "Worker ownership was lost.", run.getError());
    }

    private static final class AgentCancelledException extends RuntimeException {
        private AgentCancelledException() {
            super("Agent run cancelled");
        }
    }

    private static void stopQuietly(AutoCloseable closeable) {
        if (closeable == null)
            return;
        try {
            closeable.close();
        } catch (Exception ignored) {
            // best-effort: cancelling the heartbeat schedule never throws meaningfully
        }
    }

    private record ExecutedTools(List<String> results,boolean allSucceeded) {}
    private ExecutedTools executeTools(List<AgentTool> available, List<ToolCall> calls, AgentContext ctx,
            AgentRun run, AgentRuntimeContext runtime, long deadlineNanos) {
        Set<String> allowedNames = available.stream().map(AgentTool::name).collect(java.util.stream.Collectors.toSet());
        List<PreparedToolCall> prepared = calls.stream()
                .map(call -> new PreparedToolCall(call, parseArgs(call.arguments()), findTool(available, call.name())))
                .toList();
        prepared.forEach(item -> {
            log.info("tool_requested runId={} tool={}", run.getId(), item.call.name());
            events.emit(run, AgentStepType.TOOL_CALL, item.call.name(), auditToolArgs(item.args));
        });

        boolean allParallelSafe = prepared.stream().allMatch(item -> item.tool != null && item.tool.parallelSafe());
        List<ToolExecutionResult> results;
        if (allParallelSafe && prepared.size() > 1) {
            List<Callable<ToolExecutionResult>> work = prepared.stream()
                    .map(item -> (Callable<ToolExecutionResult>) () -> toolExecutor.execute(item.call.name(),
                            allowedNames, item.args, ctx))
                    .toList();
            results = toolExecutor.executeParallel(work, deadlineNanos);
        } else {
            results = new ArrayList<>(prepared.size());
            for (PreparedToolCall item : prepared) {
                requireBeforeDeadline(deadlineNanos);
                requireNotCancelled(run);
                results.add(toolExecutor.execute(item.call.name(), allowedNames, item.args, ctx));
            }
        }
        for (int index = 0; index < prepared.size(); index++) {
            PreparedToolCall item = prepared.get(index);
            ToolExecutionResult result = results.get(index);
            if (result.success())
                recordPhase(runtime, item.call.name());
            log.info("tool_{} runId={} tool={}", result.success() ? "completed" : "failed", run.getId(),
                    item.call.name());
            events.emit(run, AgentStepType.TOOL_RESULT, item.call.name(), auditToolResult(result, item.args));
        }
        boolean wrote = false;
        for (int index = 0; index < prepared.size(); index++) {
            wrote |= results.get(index).success() && FILE_CHANGING_TOOLS.contains(prepared.get(index).call.name());
        }
        if (wrote && drafts != null && ctx.mode() == ProjectType.BUILD) drafts.changed(run.getProjectId(), run.getId(), ctx.root());
        return new ExecutedTools(results.stream().map(this::toModelToolResult).toList(),results.stream().allMatch(result->result.success()));
    }

    /** The review turn: the request again, and the quality checklist from the build prompt as questions. */
    static String polishPrompt(String userRequest) {
        return """
                POLISH CHECK. The first version validates. Before it is shown to the user, review it against the
                request and improve it in this turn if anything is missing or rough:
                - Is every part of the request implemented and visible in the UI? Add what is missing.
                - Is the obvious scope complete (for a game: score, both choices, result, replay/reset; for lists:
                  add, complete, edit, delete, filter, persistence; for forms: validation and confirmation)?
                - Does it look finished: Tailwind styling, a clear layout, an accent color, hover/focus states,
                  transitions, and does it work at 375px wide?
                - Are empty, loading and error states handled, with real content (no placeholders)?
                Re-read files with readFile before patching them. If something needs improving, change the files
                now. If everything is already done, reply with the final summary for the user without calling tools.
                The request was:
                <request>
                %s
                </request>
                """.formatted(userRequest == null ? "" : userRequest.strip());
    }

    private static String truncateNarration(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= 2_000 ? trimmed : trimmed.substring(0, 2_000) + "...";
    }

    private static void recordPhase(AgentRuntimeContext runtime, String toolName) {
        if (toolName.equals("setPlan"))
            runtime.transitionPhase(AgentPhase.PLAN);
        else if (toolName.equals("updatePlanStep") && runtime.phase().ordinal() < AgentPhase.PLAN.ordinal())
            runtime.transitionPhase(AgentPhase.PLAN);
        else if (FILE_CHANGING_TOOLS.contains(toolName))
            runtime.transitionPhase(AgentPhase.MODIFY);
        else if (Set.of("getDiagnostics", "runTypeCheck", "runBuild", "runTests", "validateChanges").contains(toolName))
            runtime.transitionPhase(AgentPhase.VALIDATE);
        else if (runtime.phase() == AgentPhase.UNDERSTAND)
            runtime.transitionPhase(AgentPhase.EXPLORE);
    }

    private AgentTool findTool(List<AgentTool> available, String name) {
        return available.stream().filter(tool -> tool.name().equals(name)).findFirst().orElse(null);
    }

    private static void requireBeforeDeadline(long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos)
            throw new AgentTimeoutException();
    }

    /** One model call may use at most the configured call budget, and never more than the run has left. */
    private java.time.Duration callBudget(long deadlineNanos) {
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, props.getLlm().getCallBudgetMs()));
        return java.time.Duration.ofNanos(Math.min(remaining, budget));
    }

    /** Routes around a failed model: briefly for a passing failure, for long when the account cannot use it. */
    private void markUnavailable(AgentProperties.ModelSelection selection, LlmException failure) {
        if (failure instanceof com.code.pal.agent.llm.LlmHttpException http && http.modelUnavailable()) {
            props.markModelUnavailable(selection, props.getLlm().getModelUnavailableCooldownMs());
            log.warn("model_unavailable_for_account provider={} model={} status={}", selection.provider(), selection.model(), http.status());
        } else {
            props.markModelUnavailable(selection);
        }
    }

    /** Text already reached the user, so retrying on another model would duplicate or contradict it. */
    private static boolean isPartialOutputFailure(LlmException failure) {
        return failure.getMessage() != null && failure.getMessage().contains("after partial output");
    }

    private static final class AgentTimeoutException extends RuntimeException {
        private AgentTimeoutException() {
            super("Agent run timed out");
        }
    }

    private static final class AgentLimitExceededException extends RuntimeException {
        private AgentLimitExceededException(String message) {
            super(message);
        }
    }

    private static final class AgentQuotaExceededException extends RuntimeException {
        private AgentQuotaExceededException(String message) {
            super(message);
        }
    }

    static long estimateInputTokens(List<LlmMessage> messages, int specChars) {
        return TokenEstimate.input(messages, specChars);
    }

    private void requireWithinDailyQuota(AgentRun run) {
        if (usage == null) return;
        long quota = usage.dailyTokenQuota();
        long used = usage.tokensUsedToday(run.getUserId());
        if (quota > 0 && used >= quota) throw new AgentQuotaExceededException(AgentUsagePolicy.QUOTA_MESSAGE);
    }

    private static final class AgentValidationFailedException extends RuntimeException {
        private AgentValidationFailedException(String message) {
            super(message);
        }
    }

    private String validationFailureMessage(java.util.UUID runId) {
        ValidationReport report = validation.lastReport(runId);
        if (report == null) {
            return "The staged changes were not successfully validated; no changes were committed.";
        }
        if(report.outcome()==com.code.pal.agent.validation.ValidationCheck.Outcome.SKIPPED)
            return "A required validation check could not run. Code was not saved. Try again once validation is available.";
        if (report.introducedDiagnostics().isEmpty()) {
            return "Validation failed after the configured repair limit; staged changes were not committed.";
        }
        String details = report.introducedDiagnostics().stream().limit(8).map(item -> {
            String location = item.file() == null ? "project"
                    : item.file()
                            + (item.line() == null ? ""
                                    : ":" + item.line() + (item.column() == null ? "" : ":" + item.column()));
            return "- " + location + " — " + item.message();
        }).collect(java.util.stream.Collectors.joining("\n"));
        return "Validation still failed after the configured repair limit. No staged changes were committed.\n"
                + details;
    }

    private static String finalValidationRepairPrompt(ValidationReport report) {
        String details = report.introducedDiagnostics().stream().limit(8).map(item -> {
            String location = item.file() == null ? "project"
                    : item.file()
                            + (item.line() == null ? ""
                                    : ":" + item.line()
                                            + (item.column() == null ? "" : ":" + item.column()));
            return "- " + location + " - " + item.message();
        }).collect(java.util.stream.Collectors.joining("\n"));
        return "The automatic final validation found errors introduced by the staged changes. "
                + "Repair only these errors, then finish so the backend can validate once more:\n" + details;
    }

    /** Refreshes the served site; a failed build keeps the previous site and does not fail the run. */
    private void buildSite(AgentRun run) {
        if (sites == null) return;
        recordPreview(run,"BUILDING");
        SiteBuildService.Result site;
        try { site=sites.build(run.getProjectId()); }
        catch(RuntimeException failed) { recordPreview(run,"FAILED");throw failed; }
        recordPreview(run,site.error() == null ? "READY" : "FAILED");
        if (site.built()) events.emit(run, AgentStepType.STATUS, "Preview built", null);
        else if (site.error() != null) events.emit(run, AgentStepType.STATUS, "Preview build failed", Map.of("error", site.error()));
    }

    private void recordPreview(AgentRun run,String state) {
        owned(run,()->runRepository.recordPreviewStatus(run.getId(),state));
        run.setPreviewStatus(state);
    }

    private static boolean hasPreviewEntrypoint(AgentContext context) {
        return java.util.stream.Stream.of(
                "index.html", "client/index.html", "client/public/index.html", "frontend/index.html",
                "dist/index.html", "build/index.html", "client/dist/index.html", "client/build/index.html",
                "frontend/dist/index.html", "frontend/build/index.html")
                .anyMatch(path -> context.files().regularFile(context.projectId(), context.root(), path));
    }

    private record PreparedToolCall(ToolCall call, Map<String, Object> args, AgentTool tool) {
    }

    private static final int AUDIT_MAX_PATHS = 50;
    private static final int AUDIT_MAX_TEXT = 300;

    /** Argument names, plus the file paths a tool works on (never file contents) for the activity timeline. */
    private static Map<String, Object> auditToolArgs(Map<String, Object> args) {
        Map<String, Object> audit = new java.util.LinkedHashMap<>();
        audit.put("argumentNames", args.keySet().stream().sorted().toList());
        audit.putAll(auditPaths(args));
        return audit;
    }

    /**
     * The safe, user-facing part of a tool's arguments: {@code path}, {@code sourcePath}, {@code targetPath},
     * the paths of {@code files} (createFiles) and the step titles of {@code setPlan}. Strings only, clipped.
     */
    static Map<String, Object> auditPaths(Map<String, Object> args) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (String key : List.of("path", "sourcePath", "targetPath")) {
            if (args.get(key) instanceof String value && !value.isBlank()) out.put(key, clipAudit(value));
        }
        if (args.get("files") instanceof List<?> files) {
            List<String> paths = files.stream()
                    .filter(Map.class::isInstance).map(file -> ((Map<?, ?>) file).get("path"))
                    .filter(String.class::isInstance).map(path -> clipAudit((String) path))
                    .limit(AUDIT_MAX_PATHS).toList();
            if (!paths.isEmpty()) out.put("paths", paths);
        }
        if (args.get("steps") instanceof List<?> steps) {
            out.put("steps", steps.stream().filter(java.util.Objects::nonNull).map(step -> clipAudit(step.toString()))
                    .limit(AUDIT_MAX_PATHS).toList());
        }
        return out;
    }

    private static String clipAudit(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= AUDIT_MAX_TEXT ? trimmed : trimmed.substring(0, AUDIT_MAX_TEXT) + "...";
    }

    static Map<String, Object> auditToolResult(ToolExecutionResult result, Map<String, Object> args) {
        if (result.success() && result.output() instanceof ValidationReport report) {
            Map<String, Object> audit = new java.util.LinkedHashMap<>();
            audit.put("success", true);
            audit.put("validationPassed", report.changeValidationPassed());
            audit.put("validationOutcome",report.outcome().name());
            audit.put("operation", report.operation().name());
            audit.put("introducedDiagnosticCount", report.introducedDiagnostics().size());
            audit.put("preExistingDiagnosticCount", report.preExistingDiagnostics().size());
            audit.put("repairAttempts", report.repairAttempts());
            audit.put("repairAttemptsRemaining", report.repairAttemptsRemaining());
            audit.put("introducedDiagnostics", report.introducedDiagnostics());
            audit.put("preExistingDiagnostics", report.preExistingDiagnostics());
            return Map.copyOf(audit);
        }
        Map<String, Object> audit = new java.util.LinkedHashMap<>();
        audit.put("success", result.success());
        if (!result.success()) audit.put("errorCode", result.error().code());
        audit.putAll(auditPaths(args));
        // A tool that decides its own paths (startProject) names them in its result.
        if(result.success() && args.get("stepId") instanceof Number id && args.get("status") instanceof String status) {
            audit.put("stepId",id.intValue());audit.put("planStatus",clipAudit(status));
        }
        if (result.success() && result.output() instanceof Map<?, ?> output && output.get("createdFiles") instanceof List<?> created) {
            audit.put("paths", created.stream().filter(String.class::isInstance).map(path -> clipAudit((String) path))
                    .limit(AUDIT_MAX_PATHS).toList());
        }
        return audit;
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank())
            return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (RuntimeException e) {
            return Map.of("__invalidJson", true);
        }
    }

    private List<AgentTool> toolsForRun(ProjectType mode) {
        return tools.all().stream()
                .filter(tool -> READ_ONLY_FOUNDATION_TOOLS.contains(tool.name())
                        || (mode != ProjectType.CHAT && PLANNING_TOOLS.contains(tool.name()))
                        || (mode == ProjectType.BUILD && WRITE_TOOLS.contains(tool.name())))
                .sorted(java.util.Comparator.comparing(AgentTool::name))
                .toList();
    }

    private String toModelToolResult(ToolExecutionResult result) {
        String json = objectMapper.writeValueAsString(result);
        if (json.length() <= TOOL_RESULT_MAX)
            return json;
        return new com.code.pal.agent.context.ToolResultMemory(objectMapper).compact(json,null,"Tool body exceeded per-result context limit");
    }

    String budgetToolResult(AgentRuntimeContext runtime, String result) {
        if (runtime.reserveContext(result.length()))
            return result;
        String exhausted = new com.code.pal.agent.context.ToolResultMemory(objectMapper).compact(result,null,"Context budget omitted tool body");
        if(!runtime.reserveContext(exhausted.length()))throw new AgentLimitExceededException("Context capacity cannot preserve the tool outcome; no further model call was sent");
        return exhausted;
    }

    private static String budgetAssistantContext(AgentRuntimeContext runtime, String content) {
        if (content == null || content.isBlank())
            return content;
        return runtime.reserveContext(content.length()) ? content : null;
    }

    private static void requireModelCallAvailable(AgentRuntimeContext runtime, RunSize size) {
        if (runtime.modelCallCount() >= Math.max(1, size.maxModelCalls())) {
            throw new AgentLimitExceededException("Agent model-call limit reached");
        }
    }

    private static void requireToolCallsAvailable(AgentRuntimeContext runtime, int requested, RunSize size) {
        if (requested < 0 || runtime.toolCallCount() + requested > Math.max(1, size.maxToolCalls())) {
            throw new AgentLimitExceededException("Agent tool-call limit reached");
        }
    }

    /**
     * The run's size tier and the limits that come with it. A project "already has code" when its files are
     * more than its instruction files (such as the brief) and uploads.
     */
    private RunSize runSize(AgentRun run, Project project, String userRequest, ProjectContext context) {
        if (!props.isSizeTiers()) return RunSize.of(SizeTier.S, List.of("size tiers off"), props);
        Set<String> instructions = Set.copyOf(context.projectMap().instructionFiles());
        boolean existing = context.projectMap().importantFiles().stream().map(ProjectFileSummary::path)
                .anyMatch(path -> !instructions.contains(path) && !path.startsWith("uploads/"));
        var choice=buildDecisions==null || run.getMode()!=ProjectType.BUILD?null:buildDecisions.state(run.getProjectId()).choice();
        SizeTierRules.Decision decision = SizeTierRules.decide(run.getMode(), userRequest, project.getInitialPrompt(), existing,choice);
        RunSize size = RunSize.of(decision.tier(), decision.reasons(), props);
        // A new project's first build also installs its dependencies, which takes minutes on a slow registry.
        return run.getMode() == ProjectType.BUILD && !existing
                ? size.withLongerTimeout(props.getLargeRunTimeoutMs(), "first build installs dependencies") : size;
    }

    private static LlmMessage toLlmMessage(ContextMessage message) {
        return switch (message.role()) {
            case "assistant" -> LlmMessage.assistant(message.content());
            default -> LlmMessage.user(message.content());
        };
    }

    private static Map<String, Object> counters(AgentRuntimeContext runtime) {
        return Map.of(
                "iterations", runtime.iteration(),
                "modelCalls", runtime.modelCallCount(),
                "toolCalls", runtime.toolCallCount(),
                "phase", runtime.phase().name(),
                "contextChars", runtime.contextChars(),
                "remainingContextChars", runtime.remainingContextChars(),
                "compactedChars", runtime.compactedChars());
    }

    private static Map<String, Object> safeCounters(AgentRuntimeContext runtime) {
        return runtime == null ? Map.of("iterations", 0, "modelCalls", 0, "toolCalls", 0) : counters(runtime);
    }

    private static AgentRunResult result(AgentRun run, AgentRuntimeContext runtime, String response, String error) {
        return new AgentRunResult(run.getId(), run.getStatus(), response, error,
                runtime == null ? 0 : runtime.iteration(),
                runtime == null ? 0 : runtime.modelCallCount(),
                runtime == null ? 0 : runtime.toolCallCount());
    }

    private void emitContextUsage(AgentRun run, ProjectContext context, AgentRuntimeContext runtime) {
        if (context == null)
            return;
        int chars = runtime == null ? context.diagnostics().approximateChars() : runtime.contextChars();
        events.emit(run, AgentStepType.STATUS, "Project context usage",
                contextAudit(context.trace().diagnostics(chars)));
    }

    private static Map<String, Object> contextAudit(ContextDiagnostics diagnostics) {
        Map<String, Object> audit = new java.util.LinkedHashMap<>();
        audit.put("selectedFiles", diagnostics.selectedFiles());
        audit.put("omittedFiles", diagnostics.omittedFiles());
        audit.put("approximateChars", diagnostics.approximateChars());
        audit.put("approximateTokens", diagnostics.approximateTokens());
        audit.put("budgetChars", diagnostics.budgetChars());
        audit.put("omittedFileCount", diagnostics.omittedFileCount());
        audit.put("omittedConversationMessages", diagnostics.omittedConversationMessages());
        audit.put("omissionCounts", diagnostics.omissionCounts());
        return audit;
    }

    private String systemPrompt(ProjectType mode) {
        String base = PromptLoader.load(Prompt.SYSTEM_BASE);
        String modePrompt = switch (mode) {
            case BUILD -> PromptLoader.load(Prompt.MODE_BUILD);
            case CHAT  -> PromptLoader.load(Prompt.MODE_CHAT);
            case PLAN  -> PromptLoader.load(Prompt.MODE_PLAN);
        };
        return base + "\n\n" + modePrompt;
    }

    private void setStatus(AgentRun run, RunStatus status) {
        run.setStatus(status);
        owned(run,()->runRepository.save(run));
    }

    /**
     * Notify the run's user when a BUILD finishes or fails (chat/plan turns don't
     * notify).
     */
    private void notifyBuild(AgentRun run, boolean ok) {
        if (run.getMode() != ProjectType.BUILD)
            return;
        notifications.notify(run.getUserId(),
                ok ? NotificationType.BUILD_FINISHED : NotificationType.BUILD_FAILED,
                ok ? "Your build finished" : "Your build failed",
                null, run.getProjectId(), null);
    }

    private void moveProject(AgentRun run, ProjectStage stage) {
        if (run.getMode() != ProjectType.BUILD)
            return;
        try {
            owned(run,()->projects.advanceLifecycle(run.getUserId(), run.getProjectId(), stage));
        } catch (com.code.pal.agent.lease.LeaseLostException lost) { throw lost;
        } catch (RuntimeException error) {
            log.warn("project_stage_update_failed project={} stage={}: {}", run.getProjectId(), stage,
                    error.getMessage());
        }
    }

    private void accountTokens(AgentRun run, LlmResult result) {
        if (result.usage() == null)
            return;
        run.setPromptTokens(run.getPromptTokens() + result.usage().promptTokens());
        run.setCompletionTokens(run.getCompletionTokens() + result.usage().completionTokens());
        owned(run,()->runRepository.addReportedTokens(run.getId(),result.usage().promptTokens(),result.usage().completionTokens()));
    }

    private void suggestBuild(AgentRun run, String prompt, List<String> attachments) {
        owned(run, () -> {
            messageRepository.findByIdAndProjectId(run.getMessageId(), run.getProjectId()).ifPresent(message -> {
                message.setSuggestedBuildText(prompt);
                message.setProposalPlanId(run.getMode() == ProjectType.PLAN ? run.getId() : null);
                message.setAttachments(attachments.isEmpty() ? null : String.join("\n", attachments));
                messageRepository.save(message);
            });
            return null;
        });
    }

    private void updateAssistant(AgentRun run, String content, MessageStatus status) {
        owned(run,()-> { messageRepository.findById(run.getMessageId()).ifPresent(message -> {
            message.setContent(content);
            message.setStatus(status);
            Message saved = messageRepository.save(message);
            realtime.sendToProject(run.getProjectId(),
                    RealtimeMessage.of("MESSAGE_UPSERT", MessageResponse.from(saved)));
        }); return null; });
    }

    private String safeError(Exception e) {
        String message = e.getMessage() == null ? "Agent run failed" : e.getMessage();
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }

    /**
     * Batches provider deltas so token-level output never fans out one write per
     * token. The live
     * broadcast runs every small batch (cheap â€” it sends only the delta), while
     * the database is only
     * rewritten on a much coarser checkpoint. Persisting the whole growing text on
     * every batch would
     * be O(n²) in bytes written for an n-char answer.
     */
    private final class DeltaBatcher {
        private final AgentRun run;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder pending = new StringBuilder();
        private long lastFlushNanos = System.nanoTime();
        private int persistedLength = 0;
        private long lastPersistNanos = System.nanoTime();

        private DeltaBatcher(AgentRun run) {
            this.run = run;
        }

        private synchronized void append(String delta) {
            if (delta == null || delta.isEmpty())
                return;
            if (content.isEmpty() && run.getRespondingAt() == null) {
                run.setRespondingAt(Instant.now());
                owned(run,()->runRepository.save(run));
            }
            content.append(delta);
            pending.append(delta);
            if (pending.length() >= Math.max(1, props.getStreamBatchChars())
                    || elapsedMs(lastFlushNanos) >= props.getStreamBatchIntervalMs())
                flush();
        }

        private synchronized void flush() {
            if (pending.isEmpty())
                return;
            String delta = pending.toString();
            pending.setLength(0);
            lastFlushNanos = System.nanoTime();
            // Live delta to every client in the room, every batch.
            realtime.sendToProject(run.getProjectId(), RealtimeMessage.of("MESSAGE_DELTA", Map.of(
                    "id", run.getMessageId(), "projectId", run.getProjectId().toString(), "delta", delta)));
            // Durable checkpoint only when enough new text has accumulated (or enough time
            // passed), so a
            // reload/reconnect mid-stream recovers recent text without rewriting the row on
            // every batch.
            if (content.length() - persistedLength >= Math.max(1, props.getStreamPersistChars())
                    || elapsedMs(lastPersistNanos) >= props.getStreamPersistIntervalMs()) {
                checkpoint();
            }
        }

        private synchronized void checkpoint() {
            persistedLength = content.length();
            lastPersistNanos = System.nanoTime();
            messageRepository.findById(run.getMessageId()).ifPresent(message -> {
                message.setContent(content.toString());
                message.setStatus(MessageStatus.STREAMING);
                messageRepository.save(message);
            });
        }

        private synchronized void complete() {
            flush();
            updateAssistant(run, content.toString(), MessageStatus.COMPLETE);
        }

        private synchronized String content() {
            return content.toString();
        }

        private static long elapsedMs(long sinceNanos) {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sinceNanos);
        }
    }
}
