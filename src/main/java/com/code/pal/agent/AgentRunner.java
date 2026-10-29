package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ContextDiagnostics;
import com.code.pal.agent.context.ContextMessage;
import com.code.pal.agent.context.ContextSelectionReason;
import com.code.pal.agent.context.ConversationCompactor;
import com.code.pal.agent.context.ProjectContext;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.context.ProjectFileSummary;
import com.code.pal.agent.domain.AgentFailureReason;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.agent.intent.UnclearReply;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import com.code.pal.agent.llm.LlmClient.ToolSpec;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.plan.ImplementationPlanning;
import com.code.pal.agent.plan.PlanningModelCalls;
import com.code.pal.agent.preview.DraftPreviewService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.BuildPrompt;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.repair.RepairFeedback;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.route.SizeTier;
import com.code.pal.agent.route.SizeTierRules;
import com.code.pal.agent.route.ModelRoutes;
import com.code.pal.agent.runtime.AgentPhase;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.runtime.AgentRuntime;
import com.code.pal.agent.runtime.AgentRuntimeContext;
import com.code.pal.agent.runtime.AgentRuntimeStatus;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.stack.ContractStackFixes;
import com.code.pal.agent.vision.GeneratedVisualReview;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentProjectMetadata;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.ToolAudit;
import com.code.pal.agent.usage.AgentUsagePolicy;
import com.code.pal.agent.validation.ProjectValidationService;
import com.code.pal.agent.validation.ValidationReport;
import com.code.pal.agent.vision.VisionService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.database.GeneratedDatabaseService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import com.code.pal.agent.change.PromotionRecoveryPendingException;
import com.code.pal.agent.change.SequentialCheckpoints;
import com.code.pal.agent.context.ToolResultMemory;
import com.code.pal.agent.contract.BatchBudget;
import com.code.pal.agent.contract.ContractCoordinator;
import com.code.pal.agent.contract.ContractModelCalls;
import com.code.pal.agent.contract.ModelOutputProgress;
import com.code.pal.agent.llm.LlmLogicalCall;
import com.code.pal.agent.contract.StaticAssets;
import com.code.pal.agent.contract.ApprovedBehaviourSetup;
import com.code.pal.agent.tool.ToolAccessPolicy;
import com.code.pal.agent.tool.CapabilityToolInventory;
import com.code.pal.agent.validation.RunBehaviorCriteria;
import com.code.pal.agent.validation.TrustedFeatureOutcomes;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.TrustedOperationPendingException;
import com.code.pal.agent.llm.RetryableLlmException;
import com.code.pal.agent.starter.AutomaticStarter;
import com.code.pal.agent.validation.ValidationCheck;
import com.code.pal.agent.validation.ProjectDiagnostic;
import com.code.pal.agent.validation.DiagnosticSeverity;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.ApprovedSpecs;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs a bounded, recoverable agent turn without holding a database transaction
 * across LLM I/O.
 */
@Slf4j
@Service
public class AgentRunner implements AgentRuntime {

    private static final int HISTORY_CANDIDATE_LIMIT = 100;
    private static final int TOOL_RESULT_MAX = 30_000;
    /** Spec batches stay well inside the free models' context windows, which all honour max_tokens. */
    private static final int SPEC_INPUT_BYTES = 32_000;
    private static final int SPEC_CONTEXT_TOKENS = 32_000;
    private static final int SPEC_PROVIDER_OVERHEAD_TOKENS = 512;
    /** Final validation failures retried with no successful file change in between before the run stops. */
    private static final int MAX_UNCHANGED_FINISH_RETRIES = 2;
    /** Room kept for tool schemas, the reply and later tool results when preloading edit files. */
    private static final int PRELOAD_HEADROOM_CHARS = 24_000;
    /** Tools that change staged files (a draft preview follows them). */
    private static final Set<String> FILE_CHANGING_TOOLS = Set.of("createFile", "createFiles", "patchFile",
            "deleteFile", "renameFile", "copyFile",
            "startProject", "refreshDependencies");

    private ProjectLeaseService leases;

    @Autowired
    void useLeases(ProjectLeaseService leases) {
        this.leases = leases;
    }

    private <T> T owned(AgentRun run, Supplier<T> action) {
        return leases == null ? action.get() : leases.guarded(run.getProjectId(), action);
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
    private SequentialCheckpoints checkpoints;

    @Autowired
    void useCheckpoints(SequentialCheckpoints checkpoints) {
        this.checkpoints = checkpoints;
    }

    private ContractCoordinator contractCoordinator;
    private ContractStackFixes deterministicFixes;
    private GeneratedVisualReview visualReview;
    private ImplementationPlanning implementationPlanning;

    @Autowired
    void useImplementationPlanning(ImplementationPlanning planning) { implementationPlanning = planning; }

    @Autowired
    void useVisualReview(GeneratedVisualReview review) {
        visualReview = review;
    }

    @Autowired
    void useDeterministicFixes(ContractStackFixes fixes) {
        deterministicFixes = fixes;
    }

    @Autowired
    void useContractCoordinator(ContractCoordinator coordinator) {
        this.contractCoordinator = coordinator;
    }

    private ApprovedSpecs approvedSpecs;
    private RunBehaviorCriteria behaviorCriteria;
    private TrustedFeatureOutcomes featureOutcomes;
    private CapabilityToolInventory toolInventory;

    @Autowired
    void useToolInventory(CapabilityToolInventory inventory) { toolInventory = inventory; }

    @Autowired
    void useFeatureOutcomes(TrustedFeatureOutcomes outcomes) { featureOutcomes = outcomes; }

    @Autowired
    void useBehaviorCriteria(RunBehaviorCriteria criteria) {
        this.behaviorCriteria = criteria;
    }

    @Autowired
    void useApprovedSpecs(ApprovedSpecs specs) {
        this.approvedSpecs = specs;
    }

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
    private BuildDecisions buildDecisions;

    @Autowired
    public void setBuildDecisions(BuildDecisions decisions) {
        buildDecisions = Objects.requireNonNull(decisions);
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

    /** Mutable state of one run, shared by its phases and its terminal handlers. */
    private static final class RunState {
        final AgentRun run;
        final ReplyStream stream;
        int repairContextRevision = -1;
        final RunCancellation cancellation;
        AutoCloseable beat;
        AgentRuntimeContext runtime;
        ProjectContext projectContext;
        AgentContext ctx;
        long startedNanos;
        long deadlineNanos;
        /** The project had no app files when the run started: its first build gets the completeness review. */
        boolean newProject;
        /** A bare go-ahead resumed the previous failed request, so the model must be given that request. */
        boolean resumedRequest;
        String attachmentContext = "";
        int truncatedResponses;
        boolean repairing;

        RunState(AgentRun run, ReplyStream stream, RunCancellation cancellation) {
            this.run = run;
            this.stream = stream;
            this.cancellation = cancellation;
        }
    }

    /** What the tool loop needs once the project context and run size are known. */
    private record LoopSetup(RunSize size, List<AgentTool> available, List<ToolSpec> specs, int specChars,
            ConversationCompactor compactor) {
    }

    private record TurnTools(List<AgentTool> available, List<ToolSpec> specs, int specChars) {}

    /** Counters and the review snapshot the BUILD completion gates keep across iterations. */
    private static final class BuildGateState {
        int implementationReminders;
        int previewReminders;
        boolean polished;
        boolean visuallyReviewed;
        String firstSummary;
        int reviewedRevision = -1;
        /** Revision of the last failed final validation and how often finishing was retried on it unchanged. */
        int failedRevision = -1;
        int unchangedRetries;
    }

    /**
     * Model choice for one run: the preferred route while it works, then each distinct untried
     * route. A stopped run, quota, accounting, terminal or partial-output failure never fails over.
     */
    private void providerFailed(AgentRun run, ModelRoutes routes, AgentProperties.ModelSelection selection,
            LlmException failure, boolean conversational) {
        routes.failed(selection, failure, () -> requireNotCancelled(run));
        log.warn("llm_provider_unavailable runId={} conversational={} provider={} model={} reason={}; "
                + "trying next configured model", run.getId(), conversational, selection.provider(),
                selection.model(), failure.getMessage());
        if (!conversational) {
            events.emit(run, AgentStepType.STATUS, "LLM provider unavailable", Map.of(
                    "provider", selection.provider(), "model", selection.model(),
                    "retryable", failure instanceof RetryableLlmException));
        }
    }

    /**
     * Execute a read-only run end to end; every terminal outcome is persisted and
     * returned.
     */
    @Override
    public AgentRunResult run(AgentRun run) {
        ReplyStream stream = replyStream(run);
        // The run's Stop signal, bound to this thread so model calls and sandbox commands made for the run
        // stop the moment it is cancelled (not only between iterations).
        RunCancellation cancellation = cancellations == null ? new RunCancellation(run.getId())
                : cancellations.register(run.getId());
        RunState state = new RunState(run, stream, cancellation);
        RunCancellation.Registration boundCancellation = RunCancellation.bind(cancellation);
        // Who the run's model calls are for, for the call ledger; the loop's own calls are counted in the run's totals.
        LlmCallContext.Scope callContext = LlmCallContext
                .bind(new LlmCallContext(run.getUserId(), run.getProjectId(), run.getId(), CallPurpose.AGENT, true));
        try {
            try {
                return execute(state);
            } finally {
                // Terminal bookkeeping must not observe the run's own Stop: fenced writes refuse a cancelled thread.
                boundCancellation.close();
            }
        } catch (PromotionRecoveryPendingException pending) {
            return saveRecoveryPending(state, pending);
        } catch (TrustedOperationPendingException pending) {
            cancellation.cancel();
            run.setError(pending.getMessage());
            return result(run, state.runtime, "Project operation cleanup is pending.", run.getError());
        } catch (LeaseLostException lost) {
            // Replacement/recovery owns durable state. A stale worker emits no terminal writes.
            return ownershipLost(run, state.runtime, cancellation);
        } catch (AgentCancelledException | LlmCancelledException e) {
            return cancelled(state);
        } catch (AgentQuotaExceededException | LlmQuotaExceededException e) {
            return quotaExceeded(state, e);
        } catch (AgentValidationFailedException e) {
            return validationFailed(state, e);
        } catch (Exception e) {
            return failed(state, e);
        } finally {
            callContext.close();
            cleanup(state);
        }
    }

    private AgentRunResult execute(RunState state) throws Exception {
        AgentRun run = state.run;
        state.startedNanos = System.nanoTime();
        // Today's limit until the run's size is known; M and L builds get the longer one below.
        state.deadlineNanos = state.startedNanos + TimeUnit.MILLISECONDS.toNanos(props.getRunTimeoutMs());
        run.setHeartbeatAt(Instant.now());
        setStatus(run, RunStatus.RUNNING);
        log.info("agent_started runId={} projectId={} userId={}", run.getId(), run.getProjectId(), run.getUserId());
        events.emit(run, AgentStepType.STATUS, "Agent started", Map.of("status", "RUNNING"));
        state.beat = heartbeat.start(run.getId(), run.getProjectId());
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
                ? accepted.request().getContent()
                : accepted.request().getResolvedRequestText();
        List<String> attachments = accepted.request().executionAttachmentPaths();
        if (run.getMode() == ProjectType.BUILD && accepted.request().getSourceProposalId() == null && attachments.isEmpty()
                && RequestRouter.confirmation(userRequest)) {
            // "ok make it" with no open offer: the user means their request just before, if its build failed
            // before saving anything (for example a restart interrupted it). Never reaches past that one turn.
            Message unfinished = unfinishedBuildRequest(history, accepted.request(), earlier -> runRepository
                    .findByProjectIdAndRequestMessageId(run.getProjectId(), earlier.getId())
                    .filter(prior -> prior.getMode() == ProjectType.BUILD && prior.getStatus() == RunStatus.FAILED
                            && prior.getCodeSavedAt() == null)
                    .isPresent());
            if (unfinished != null) {
                // The whole original request resumes: its server-resolved text (an accepted offer or plan) and its
                // attachments, so a resumed build neither drops an image nor re-plans an accepted plan.
                log.info("confirmation_resumes_request runId={}", run.getId());
                userRequest = unfinished.getResolvedRequestText() == null ? unfinished.getContent()
                        : unfinished.getResolvedRequestText();
                attachments = unfinished.executionAttachmentPaths();
                state.resumedRequest = true;
            }
        }
        RequestRouter.Route route = RequestRouter.route(run.getMode(), userRequest, attachments, false);
        ProjectType effectiveMode = RequestRouter.mode(run.getMode(), userRequest, attachments);
        if (effectiveMode != run.getMode()) {
            run.setMode(effectiveMode);
            owned(run, () -> runRepository.save(run));
        }
        String originalOffer = accepted.request().getSourceProposalId() == null ? accepted.request().getContent()
                : messageRepository
                        .findByIdAndProjectId(accepted.request().getSourceProposalId(), run.getProjectId())
                        .map(Message::getSuggestedBuildText).orElse(accepted.request().getContent());
        if (route == RequestRouter.Route.CHANGE && run.getMode() != ProjectType.BUILD
                || route == RequestRouter.Route.QUESTION && RequestRouter.positiveChange(userRequest)) {
            suggestBuild(run, originalOffer, attachments);
        }
        if (route == RequestRouter.Route.SMALL_TALK
                || route == RequestRouter.Route.UNCLEAR || RequestRouter.productQuestion(userRequest)) {
            return runConversational(run, state.stream, history, userRequest, state.deadlineNanos,
                    route == RequestRouter.Route.UNCLEAR, attachments);
        }

        requireBuildAllowed(run);
        LoopSetup setup = prepareLoop(state, project, accepted, userRequest, attachments);
        state.newProject = projectShape(run.getProjectId(), state.ctx.root()).equals(BuildPrompt.ProjectShape.EMPTY);
        if (contractCoordinator != null) {
            ContractCoordinator.SpecPlanning planning = specPlanning(run, state.ctx.root());
            if (contractCoordinator.eligible(run, setup.size(), userRequest, planning)) {
                try {
                    return runStructured(state, setup.size(), userRequest, planning);
                } catch (IllegalArgumentException invalidPlan) {
                    // A plan the model could not produce, before any file changed, is not worth failing the build:
                    // the ordinary tool loop builds it from the same request within the remaining budget.
                    if (run.getMode() != ProjectType.BUILD || approvedStorage(run) == BuildDecisions.Storage.MERN
                            || !changeSets.view(state.ctx).touchedPaths().isEmpty()) {
                        throw invalidPlan;
                    }
                    log.warn("structured_plan_fallback runId={} reason={}", run.getId(), invalidPlan.getMessage());
                    events.emit(run, AgentStepType.STATUS, "Building step by step", null);
                }
            }
        }
        prepareStarter(state, project, setup, userRequest);
        planNewApplication(state, setup);
        if (run.getMode() == ProjectType.BUILD && !state.ctx.plan().exists()) {
            // An approved build may edit straight away; the backend records a minimal plan the model can revise,
            // instead of spending a turn on setPlan. Read-only runs never get one.
            state.ctx.plan().revise(List.of("Apply the requested change", "Validate the result"),
                    "Backend plan for a direct change");
        }
        return runToolLoop(state, setup, userRequest);
    }

    /**
     * The same author's request message just before this go-ahead, when the reply right after it is a failed build that
     * saved no code (for example a restart interrupted it); else null. A clarifying question, a chat answer or a
     * build that saved code is never resumed.
     */
    static Message unfinishedBuildRequest(List<Message> history, Message request,
                                         Predicate<Message> failedUnsavedBuild) {
        int at = history.indexOf(request);
        if (at < 2) {
            return null;
        }
        Message reply = history.get(at - 1);
        Message earlier = history.get(at - 2);
        boolean answers = reply.getRole() == MessageRole.ASSISTANT && earlier.getRole() == MessageRole.USER
                && earlier.getId().equals(reply.getReplyToId());
        if (!answers || reply.getStatus() != MessageStatus.FAILED
                || !Objects.equals(earlier.getAuthorId(), request.getAuthorId())
                || !Objects.equals(earlier.getProjectId(), request.getProjectId())
                || RequestRouter.route(earlier.getResolvedRequestText() == null ? earlier.getContent()
                        : earlier.getResolvedRequestText()) != RequestRouter.Route.CHANGE
                || !failedUnsavedBuild.test(earlier)) {
            return null;
        }
        return earlier;
    }

    private void requireBuildAllowed(AgentRun run) {
        if (run.getMode() == ProjectType.BUILD && buildDecisions != null) {
            var decision = buildDecisions.state(run.getProjectId());
            if (decision.blocked()) {
                throw new WorkflowStageException(decision.problem());
            }
            if (decision.choice() != null && decision.choice().storage() == BuildDecisions.Storage.SUPABASE
                    && (!props.getSupabaseValidation().isEnabled() || !props.getSandbox().isRuntimeCheck() || !props.getSandbox().isRequired())) {
                throw new WorkflowStageException("Cloud builds require isolated Supabase validation, runtime checks and the Docker sandbox; "
                        + "these are unavailable on this deployment");
            }
        }
        if (run.getMode() == ProjectType.BUILD) {
            moveProject(run, ProjectStage.BUILDING);
        }
    }

    private LoopSetup prepareLoop(RunState state, Project project, RunRequestHistory accepted, String userRequest,
            List<String> attachments) {
        AgentRun run = state.run;
        // BUILD runs work on a staged copy committed at the end; other runs read the project itself.
        String root = run.getMode() == ProjectType.BUILD ? changeSets.start(run.getId(), run.getProjectId()) : ".";
        state.projectContext = projectContexts.build(files, run.getProjectId(), root, userRequest,
                accepted.messages(), List.of());
        RunSize size = runSize(run, project, userRequest, state.projectContext);
        if (usage != null) {
            usage.configureRun(run.getId(), run.getUserId(), size.tier());
        }
        state.deadlineNanos = state.startedNanos + TimeUnit.MILLISECONDS.toNanos(size.timeoutMs());
        events.emit(run, AgentStepType.STATUS, "Run size",
                Map.of("tier", size.tier().name(), "reasons", size.reasons(),
                        "timeoutMs", size.timeoutMs(), "maxIterations", size.maxIterations()));
        SequentialCheckpoints.Progress durableProgress = null;
        if (checkpoints != null) {
            durableProgress = checkpoints.configure(run, size);
            state.deadlineNanos = Math.min(state.deadlineNanos,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durableProgress.remainingMs()));
        }
        AgentPlanState plan = new AgentPlanState();
        state.ctx = new AgentContext(run.getProjectId(), run.getUserId(), run.getMode(), root,
                AgentProjectMetadata.from(project), state.projectContext.trace(), run.getId(), plan, files);
        state.ctx.contextTrace().patchMissLimit(props.getMaxPatchMisses());
        String resumed = run.getMode() == ProjectType.BUILD ? changeSets.resumePlan(state.ctx) : null;
        List<AgentTool> available = toolsForRun(run.getMode());
        List<ToolSpec> specs = available.stream()
                .map(t -> new ToolSpec(t.name(), t.description(), t.parametersSchema())).toList();
        int specChars = specs.toString().length();
        ConversationCompactor compactor = props.isCompactContext()
                ? new ConversationCompactor(objectMapper, props.getCompactKeepTurns(), props.getCompactMinChars())
                : null;

        List<LlmMessage> convo = initialConversation(state, accepted, userRequest, attachments, resumed);
        int initialContextChars = convo.stream()
                .mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum()
                + specs.toString().length();
        state.runtime = new AgentRuntimeContext(run.getId(), run.getProjectId(), run.getUserId(), userRequest, convo,
                props.getContextBudgetChars(), initialContextChars, plan);
        if (durableProgress != null) {
            state.runtime.restoreCounters(durableProgress.iterations(), durableProgress.modelCalls(),
                    durableProgress.toolCalls());
        }
        state.runtime.transitionTo(AgentRuntimeStatus.RUNNING);
        state.runtime.transitionPhase(AgentPhase.EXPLORE);
        events.emit(run, AgentStepType.STATUS, "Project context prepared",
                contextAudit(state.projectContext.diagnostics()));
        return new LoopSetup(size, available, specs, specChars, compactor);
    }

    private List<LlmMessage> initialConversation(RunState state, RunRequestHistory accepted, String userRequest,
            List<String> attachments, String resumed) {
        AgentRun run = state.run;
        List<LlmMessage> convo = new ArrayList<>();
        convo.add(LlmMessage.system(systemPrompt(run, state.ctx.root())));
        if (!state.projectContext.projectContextMessage().isBlank()) {
            convo.add(LlmMessage.user(state.projectContext.projectContextMessage()));
        }
        state.projectContext.conversation().stream().map(AgentRunner::toLlmMessage).forEach(convo::add);
        if (accepted.request().getResolvedRequestText() != null || state.resumedRequest) {
            convo.add(LlmMessage.user(userRequest));
        }
        if (!attachments.isEmpty() && vision != null) {
            events.emit(run, AgentStepType.STATUS, "Reading attachments", Map.of("files", attachments));
            state.attachmentContext = VisionService.asContext(vision.describe(run.getProjectId(), attachments, userRequest));
            convo.add(LlmMessage.user(state.attachmentContext));
        }
        if (resumed != null) {
            convo.add(LlmMessage.user(resumed));
        } else if (attachments.isEmpty()) {
            preloadRelevantFiles(state, userRequest, convo);
        }
        if (run.getMode() == ProjectType.BUILD) {
            requireApprovedBehaviour(state, convo);
            if (featureOutcomes != null) {
                String guidance = featureOutcomes.guidance(featureOutcomes.plan(run.getProjectId()));
                if (!guidance.isEmpty()) convo.add(LlmMessage.user(guidance));
            }
        }
        return convo;
    }

    /**
     * A new app's first tool-loop build is checked against what the user approved, not only "the page loads":
     * approved model scenarios become this run's advisory browser recipe, alongside independent platform
     * checks. A missing spec keeps generic smoke. A run resumed after a
     * checkpoint gets the same guidance back for the checks it already installed, never different ones.
     */
    private void requireApprovedBehaviour(RunState state, List<LlmMessage> convo) {
        AgentRun run = state.run;
        if (approvedSpecs == null || behaviorCriteria == null || !props.getSandbox().isRuntimeCheck()) {
            return;
        }
        var setup = new ApprovedBehaviourSetup(approvedSpecs, behaviorCriteria, props).prepare(state.ctx,
                () -> projectShape(run.getProjectId(), state.ctx.root()).equals(BuildPrompt.ProjectShape.EMPTY));
        setup.guidance().forEach(guidance -> convo.add(LlmMessage.user(guidance)));
        if (setup.unverified() != null) {
            var scope = setup.unverified();
            log.info("approved_behaviour_unverified runId={} storage={} auth={} readiness={}", run.getId(),
                    scope.storage(), scope.auth(), scope.readiness());
            events.emit(run, AgentStepType.STATUS, "Approved behaviour is not verified for cloud data or accounts yet",
                    Map.of("verified", false, "reason", "CONNECTOR_ADAPTER_PENDING"));
        }
    }

    /**
     * Supply complete relevant source for edits and project questions without a separate readFile call.
     * Bodies count against the context budget and are recorded as
     * reads only after they are in the conversation; anything missing is still read with the normal tools.
     */
    private void preloadRelevantFiles(RunState state, String userRequest, List<LlmMessage> convo) {
        var trace = state.ctx.contextTrace();
        if (trace == null || projectShape(state.run.getProjectId(), state.ctx.root()).equals(BuildPrompt.ProjectShape.EMPTY)) {
            return;
        }
        int used = convo.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum();
        int remaining = props.getContextBudgetChars() - used - PRELOAD_HEADROOM_CHARS;
        if (remaining <= 0) {
            return;
        }
        List<String> targets = trace.diagnostics(0).selectedFiles().stream()
                .filter(selected -> selected.reason() == ContextSelectionReason.USER_MENTIONED
                        || selected.reason() == ContextSelectionReason.SEARCH_MATCH)
                .map(ContextDiagnostics.SelectedPath::path).toList();
        if (targets.isEmpty()) targets = state.projectContext.projectMap().importantFiles().stream()
                .filter(file -> file.category().equals("entry-point")).limit(3).map(ProjectFileSummary::path).toList();
        var preloaded = state.run.getMode() == ProjectType.BUILD ? projectContexts.preloadedFiles(state.ctx, userRequest, targets, remaining)
                : projectContexts.preloadedReadOnlyFiles(state.ctx, userRequest, targets, remaining);
        if (preloaded.text().isBlank()) {
            return;
        }
        convo.add(LlmMessage.user(preloaded.text()));
        preloaded.versions().forEach((path, version) -> trace.recordRead(path,
                files.size(state.run.getProjectId(), state.ctx.root(), path), version,
                (int) Math.min(Integer.MAX_VALUE, files.size(state.run.getProjectId(), state.ctx.root(), path))));
    }

    /**
     * The approved spec plans a new project's first build and resumes or reuses its versioned plan;
     * ordinary later edits use the tool loop.
     */
    private ContractCoordinator.SpecPlanning specPlanning(AgentRun run, String root) {
        if (approvedSpecs == null || !props.isStructuredContracts()
                || !(contractCoordinator.needsSpecPlanning(run)
                        || projectShape(run.getProjectId(), root).equals(BuildPrompt.ProjectShape.EMPTY))) {
            return null;
        }
        var budget = new BatchBudget.Limits(SPEC_INPUT_BYTES, props.getLlm().getMaxOutputTokens(), SPEC_CONTEXT_TOKENS,
                SPEC_PROVIDER_OVERHEAD_TOKENS, true);
        return approvedSpecs.current(run.getProjectId())
                .map(spec -> new ContractCoordinator.SpecPlanning(spec, budget)).orElse(null);
    }

    /**
     * One logical structured request: the preferred FREE route, then up to two other configured FREE routes when
     * an attempt provably produced no completion. Each failed attempt's model/iteration slot is refunded durably
     * once; tokens, tools and elapsed time are not.
     */
    private LlmResult structuredCall(AgentRun run, AgentRuntimeContext runtime, long deadline, LlmRequest request,
            Function<LlmRequest, LlmResult> attempt) {
        var purpose = LlmCallContext.current().purpose();
        if (purpose == CallPurpose.PLANNER || purpose == CallPurpose.SCHEMA_CORRECTION) {
            // The route timeout bounds each physical call; failover shares only the overall run deadline.
            return new PlanningModelCalls(props).execute(request,
                    Duration.ofNanos(Math.max(0, deadline - System.nanoTime())), attempt, () -> {
                requireNotCancelled(run);
                requireBeforeDeadline(deadline);
            });
        }
        var routes = props.freeStructuredRoutes();
        var preferred = new AgentProperties.ModelSelection(request.provider(), request.model());
        if (routes.size() < 2 || !routes.contains(preferred)) {
            return attempt.apply(request);
        }
        var budgeted = request.budget() != null ? request : new LlmRequest(request.provider(), request.model(),
                request.messages(), request.tools(), request.temperature(), callBudget(deadline), request.toolChoice(),
                request.maxTokens(), request.jsonSchema());
        return new ContractModelCalls(routes, System::nanoTime).execute(budgeted, new ContractModelCalls.Attempts() {
            @Override
            public LlmResult call(UUID logicalId, int ordinal, LlmRequest routed) {
                try (var metered = LlmLogicalCall.bind(attemptId(logicalId, ordinal))) {
                    return attempt.apply(routed);
                }
            }

            /** A refused request (rate limit, server error status, model unavailable) with settled ledger proof only. */
            @Override
            public boolean noCompletion(UUID logicalId, int ordinal, RuntimeException failure) {
                boolean refused = failure instanceof RetryableLlmException retryable
                        && (retryable.rateLimited() || retryable.getMessage() != null && retryable.getMessage().startsWith("HTTP 5"))
                        || failure instanceof LlmHttpException http && http.modelUnavailable();
                return refused && checkpoints.provedNoCompletion(run, attemptId(logicalId, ordinal));
            }

            @Override
            public void refund(UUID logicalId, int ordinal) {
                if (checkpoints.refundModelIteration(run, attemptId(logicalId, ordinal), ordinal)) {
                    synchronized (runtime) {
                        runtime.refundModelCall();
                    }
                }
            }
        });
    }

    private static UUID attemptId(UUID logicalId, int ordinal) {
        return UUID.nameUUIDFromBytes((logicalId + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    private AgentRunResult runStructured(RunState state, RunSize size, String userRequest,
            ContractCoordinator.SpecPlanning planning) {
        AgentRun run = state.run;
        if (checkpoints == null) {
            throw new AgentLimitExceededException("Durable structured execution is unavailable");
        }
        final AgentRuntimeContext structuredRuntime = state.runtime;
        final long structuredDeadline = state.deadlineNanos;
        Function<LlmRequest, LlmResult> attempt = request -> {
            long currentDeadline = Math.min(structuredDeadline, System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(checkpoints.progress(run.getId()).remainingMs()));
            requireBeforeDeadline(currentDeadline);
            requireNotCancelled(run);
            requireWithinDailyQuota(run);
            if (request.messages().stream().mapToInt(m -> m.content() == null ? 0 : m.content().length())
                    .sum() > props.getContextBudgetChars()) {
                throw new AgentLimitExceededException("Structured context limit reached");
            }
            synchronized (structuredRuntime) {
                requireModelCallAvailable(structuredRuntime, size);
                if (!checkpoints.reserveModelIteration(run)) {
                    throw new AgentLimitExceededException("Durable model/iteration/deadline limit reached");
                }
                structuredRuntime.beginIteration();
                structuredRuntime.recordModelCall();
            }
            var budget = callBudget(currentDeadline);
            if (request.budget() != null && request.budget().compareTo(budget) < 0) {
                budget = request.budget();
            }
            var bounded = new LlmRequest(request.provider(), request.model(), request.messages(),
                    request.tools(), request.temperature(), budget, request.toolChoice(), request.maxTokens(),
                    request.jsonSchema());
            var response = llm.chat(bounded);
            synchronized (run) {
                accountTokens(run, response);
            }
            return response;
        };
        var progress = new ModelOutputProgress(props, objectMapper, repeated -> {
            log.warn("model_no_progress runId={} provider={} model={} wastedCalls={}", run.getId(),
                    repeated.route().provider(), repeated.route().model(), repeated.wastedCalls());
            events.emit(run, AgentStepType.STATUS, "Model output made no progress", Map.of(
                    "provider", repeated.route().provider(), "model", repeated.route().model(), "wastedCalls", repeated.wastedCalls()));
        });
        Function<LlmRequest, LlmResult> checkedCall = request -> structuredCall(run, structuredRuntime, structuredDeadline,
                request, routed -> progress.call(routed, attempt));
        Runnable beforeIo = () -> {
            requireNotCancelled(run);
            requireBeforeDeadline(structuredDeadline);
            requireToolCallsAvailable(structuredRuntime, 1, size);
            if (!checkpoints.reserveTools(run, 1)) {
                throw new AgentLimitExceededException("Durable operation/deadline limit reached");
            }
            structuredRuntime.recordToolCalls(1);
        };
        String structuredRequest = state.attachmentContext.isBlank() ? userRequest : userRequest + "\n" + state.attachmentContext;
        var outcome = contractCoordinator.execute(state.ctx, run, size, structuredRequest,
                state.projectContext.projectContextMessage(), checkedCall, beforeIo,
                title -> events.emit(run, AgentStepType.STATUS, title, Map.of("progress", true)), planning);
        if (run.getMode() == ProjectType.BUILD) {
            beforeIo.run();
            projects.requireCanEdit(run.getUserId(), run.getProjectId());
            if (!hasPreviewEntrypoint(state.ctx)) {
                throw new AgentValidationFailedException("The structured build has no preview entrypoint");
            }
            commitBuild(run, state.ctx, state.stream, outcome.summary(), "The structured build produced no changes");
        } else {
            state.stream.append(outcome.summary());
        }
        return completeRun(state);
    }

    private void prepareStarter(RunState state, Project project, LoopSetup setup, String userRequest) {
        AgentRun run = state.run;
        AgentContext ctx = state.ctx;
        if (run.getMode() != ProjectType.BUILD || !props.isAutomaticStarter() || buildDecisions == null) {
            return;
        }
        var approvedStack = approvedSpecs == null ? null : approvedSpecs.current(run.getProjectId())
                .map(spec -> spec.desired().renderStack()).orElse(null);
        var starter = AutomaticStarter.select(ctx, buildDecisions.state(run.getProjectId()).choice(), userRequest,
                approvedStack, props.getDefaultRenderStack());
        if (starter == null || setup.available().stream().noneMatch(tool -> tool.name().equals("startProject"))) {
            return;
        }
        requireBeforeDeadline(state.deadlineNanos);
        requireNotCancelled(run);
        requireToolCallsAvailable(state.runtime, 1, setup.size());
        if (checkpoints != null && !checkpoints.reserveTools(run, 1)) {
            throw new AgentLimitExceededException("Durable preparation limit reached");
        }
        if (!ctx.plan().exists()) {
            ctx.plan().revise(List.of("Prepare the approved application setup",
                    "Implement the requested features", "Validate the application"),
                    "Backend starter preparation");
        }
        state.runtime.recordToolCalls(1);
        String arguments = objectMapper.writeValueAsString(Map.of("stack", starter.id(), "title",
                project.getName() == null ? "App" : project.getName()));
        var setupResult = executeTools(setup.available(),
                List.of(new ToolCall("backend_prepare", "startProject", arguments)),
                ctx, run, state.runtime, state.deadlineNanos);
        if (!setupResult.allSucceeded()) {
            throw new AgentValidationFailedException("Approved starter preparation failed; no code was saved");
        }
        String prepared = objectMapper.writeValueAsString(AutomaticStarter.context(ctx, starter));
        String data = PromptLoader.load(Prompt.STARTER_PREPARED).replace("{{starter}}", prepared);
        if (!state.runtime.reserveContext(data.length())) {
            throw new AgentLimitExceededException("Prepared starter exceeds context capacity");
        }
        state.runtime.messages().add(LlmMessage.user(data));
    }

    private void planNewApplication(RunState state, LoopSetup setup) {
        if (implementationPlanning == null || !state.newProject || state.run.getMode() != ProjectType.BUILD
                || state.runtime.modelCallCount() != 0) return;
        var planTool = setup.available().stream().filter(tool -> tool.name().equals("setPlan")).findFirst()
                .orElseThrow(() -> new AgentValidationFailedException("Application planning is unavailable"));
        requireIterationAllowed(state, setup);
        var model = props.preferredModel(ProjectType.PLAN);
        var planned = implementationPlanning.plan(model, state.runtime.messages(), planTool,
                callBudget(state.deadlineNanos), props.getLlm().getMaxOutputTokens(), request -> {
                    if (contextChars(request.messages(), request.tools().toString().length()) > props.getContextBudgetChars()) {
                        throw new AgentLimitExceededException("Application planning exceeds context capacity");
                    }
                    return new PlanningModelCalls(props).execute(request, routed -> {
                        requireIterationAllowed(state, setup);
                        reserveModelCall(state, setup.size(), new AgentProperties.ModelSelection(routed.provider(), routed.model()));
                        var result = llm.chat(routed);
                        accountTokens(state.run, result);
                        return result;
                    }, () -> {
                        requireNotCancelled(state.run);
                        requireBeforeDeadline(state.deadlineNanos);
                    });
                });
        var spec = new ToolSpec(planTool.name(), planTool.description(), planTool.parametersSchema());
        int previousPlanVersion = state.ctx.plan().snapshot().version();
        runToolCalls(state, setup, new TurnTools(List.of(planTool), List.of(spec), spec.toString().length()),
                planned, new ModelRoutes(props, ProjectType.PLAN));
        if (state.ctx.plan().snapshot().version() <= previousPlanVersion) {
            throw new AgentValidationFailedException("Application plan was not accepted");
        }
    }

    private AgentRunResult runToolLoop(RunState state, LoopSetup setup, String userRequest) {
        AgentRun run = state.run;
        AgentRuntimeContext runtime = state.runtime;
        AgentContext ctx = state.ctx;
        BuildGateState gates = new BuildGateState();
        // One review turn per build, once the change validates: weak models stop at the first
        // version that runs, and the checklist brings back what they skipped.
        gates.polished = !props.isPolishTurn();
        // One model for the whole run (the first available in priority order); another one only for
        // the calls made while it is failing.
        ModelRoutes routes = new ModelRoutes(props, run.getMode());
        for (int i = runtime.iteration(); i < setup.size().maxIterations(); i++) {
            requireIterationAllowed(state, setup);
            TurnTools turnTools = turnTools(setup, ctx);
            if (contextChars(runtime.messages(), turnTools.specChars()) > props.getContextBudgetChars()) {
                throw new AgentLimitExceededException("Context including active tool schemas exceeds configured capacity; no model call was sent");
            }
            AgentProperties.ModelSelection selection = routes.next();
            reserveModelCall(state, setup.size(), selection);
            LlmResult res;
            try (var purpose = LlmCallContext.purpose(state.repairing ? CallPurpose.REPAIR : CallPurpose.AGENT, true)) {
                // A build's reply is its final summary: what the model says between tool calls is
                // narration for the timeline, not part of the answer.
                // A build cannot finish before it has a plan and has changed files, so until then the
                // model must call a tool (weak models otherwise answer in prose and stop).
                boolean mustAct = run.getMode() == ProjectType.BUILD
                        && (!ctx.plan().exists() || changeSets.view(ctx).touchedPaths().isEmpty());
                res = llm.chatStreaming(new LlmRequest(
                        selection.provider(), selection.model(), runtime.messages(), turnTools.specs(),
                        props.getLlm().getTemperature(), callBudget(state.deadlineNanos),
                        mustAct ? "required" : null, props.getLlm().getMaxOutputTokens()),
                        run.getMode() == ProjectType.BUILD ? delta -> {
                        } : state.stream::append);
            } catch (LlmException providerFailure) {
                providerFailed(run, routes, selection, providerFailure, false);
                continue;
            }
            props.markModelAvailable(selection);
            state.stream.flush();
            accountTokens(run, res);
            if ("length".equals(res.finishReason())) {
                if (run.getMode() != ProjectType.BUILD
                        || ++state.truncatedResponses > Math.max(0, props.getMaxTruncatedResponses())) {
                    throw new AgentLimitExceededException("Model output repeatedly exceeded its token limit; incomplete output was not saved");
                }
                runtime.messages().add(LlmMessage.user(PromptLoader.load(Prompt.TRUNCATED_OUTPUT)));
                events.emit(run, AgentStepType.STATUS, "Continuing with smaller file batches", null);
                continue;
            }
            emitThinking(run, res);

            boolean completionRequested = false;
            if (res.hasToolCalls()) {
                completionRequested = runToolCalls(state, setup, turnTools, res, routes);
                if (!completionRequested) {
                    continue;
                }
            }
            if (run.getMode() == ProjectType.BUILD) {
                if (!passBuildGates(state, gates, res, userRequest, routes, setup.size())) {
                    continue;
                }
                // Before the commit: committing closes the change set, and with it its revision.
                String answer = gates.reviewedRevision >= 0
                        && changeSets.view(ctx).revision() == gates.reviewedRevision ? gates.firstSummary
                                : res.content();
                String summary = completionRequested
                        ? objectMapper.readTree(res.toolCalls().getLast().arguments()).path("summary").asString("")
                        : answer;
                events.emit(run, AgentStepType.STATUS, "Saving changes", null);
                commitBuild(run, ctx, state.stream, summary, "The staged build produced no committed file changes");
            }
            return completeRun(state);
        }
        throw new AgentLimitExceededException("Agent iteration limit reached");
    }

    /** Checks done before every model call: deadline, Stop, repair and patch limits, context size. */
    private void requireIterationAllowed(RunState state, LoopSetup setup) {
        AgentRun run = state.run;
        AgentContext ctx = state.ctx;
        requireBeforeDeadline(state.deadlineNanos);
        requireNotCancelled(run);
        // Terminate known failure before reserving/calling a model whose reply would be discarded.
        if (run.getMode() == ProjectType.BUILD && changeSets.validationProgress(ctx).repairLimitExhausted()) {
            throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
        }
        if (ctx.contextTrace().patchLimitReached()) {
            throw new AgentValidationFailedException("Repeated patch misses; staged changes were not committed. "
                    + "Re-read and revise the approach for: " + ctx.contextTrace().patchBlockedPaths());
        }
        if (setup.compactor() != null) {
            ConversationCompactor.Saved saved = setup.compactor().compact(state.runtime.messages());
            saved.omittedReadPaths().forEach(ctx.contextTrace()::forgetRead);
            if (saved.total() > 0) {
                state.runtime.recordCompaction(saved.resultChars(), saved.argumentChars());
            }
        }
        if (contextChars(state.runtime.messages(), 0) > props.getContextBudgetChars()) {
            throw new AgentLimitExceededException(
                    "Context including tool arguments exceeds configured capacity; no further model call was sent");
        }
    }

    private TurnTools turnTools(LoopSetup setup, AgentContext context) {
        if (toolInventory == null || !props.isCompactToolCatalog()) return new TurnTools(setup.available(), setup.specs(), setup.specChars());
        var active = toolInventory.select(setup.available(), context);
        var specs = active.stream().map(tool -> new ToolSpec(tool.name(), tool.description(), tool.parametersSchema())).toList();
        return new TurnTools(active, specs, specs.toString().length());
    }

    private long contextChars(List<LlmMessage> messages, int specChars) {
        long chars = specChars;
        for (var message : messages) {
            chars += message.content() == null ? 0 : message.content().length();
            if (message.toolCalls() == null) {
                continue;
            }
            for (var call : message.toolCalls()) {
                chars += call.arguments() == null ? 0 : call.arguments().length();
                if (call.extraContent() != null) {
                    chars += objectMapper.writeValueAsString(call.extraContent()).length();
                }
            }
        }
        return chars;
    }

    private void reserveModelCall(RunState state, RunSize size, AgentProperties.ModelSelection selection) {
        AgentRun run = state.run;
        AgentRuntimeContext runtime = state.runtime;
        if (checkpoints != null && !checkpoints.reserveModelIteration(run)) {
            throw new AgentLimitExceededException("Durable model/iteration/deadline limit reached");
        }
        runtime.beginIteration();
        requireModelCallAvailable(runtime, size);
        requireWithinDailyQuota(run);
        runtime.transitionTo(AgentRuntimeStatus.WAITING_FOR_MODEL);
        runtime.recordModelCall();
        log.info("model_called runId={} iteration={} modelCall={} provider={} model={}", run.getId(),
                runtime.iteration(), runtime.modelCallCount(), selection.provider(), selection.model());
        events.emit(run, AgentStepType.STATUS, "Model called", Map.of(
                "iteration", runtime.iteration(), "modelCall", runtime.modelCallCount()));
    }

    private void emitThinking(AgentRun run, LlmResult res) {
        boolean hasReasoning = res.reasoning() != null && !res.reasoning().isBlank();
        // In a build every message is kept for the timeline: with tools it is narration, without
        // them it is either the final answer or a reply a gate will send back.
        boolean narrated = res.content() != null && !res.content().isBlank()
                && (res.hasToolCalls() || run.getMode() == ProjectType.BUILD);
        if (!hasReasoning && !narrated) {
            return;
        }
        Map<String, Object> thinking = new HashMap<>();
        if (hasReasoning) {
            thinking.put("reasoning", res.reasoning());
        }
        if (narrated) {
            thinking.put("narration", truncateNarration(res.content()));
        }
        thinking.put("toolCallCount", res.toolCalls().size());
        events.emit(run, AgentStepType.THINKING, "Model reasoning", thinking);
    }

    /** Executes the model's tool calls; true when a BUILD asked to finish and every call succeeded. */
    private boolean runToolCalls(RunState state, LoopSetup setup, TurnTools turnTools, LlmResult res, ModelRoutes routes) {
        AgentRun run = state.run;
        AgentRuntimeContext runtime = state.runtime;
        AgentContext ctx = state.ctx;
        requireToolCallsAvailable(runtime, res.toolCalls().size(), setup.size());
        if (checkpoints != null && !checkpoints.reserveTools(run, res.toolCalls().size())) {
            throw new AgentLimitExceededException("Durable tool/deadline limit reached");
        }
        runtime.recordToolCalls(res.toolCalls().size());
        runtime.messages().add(new LlmMessage("assistant", budgetAssistantContext(runtime, res.content()),
                res.toolCalls(), null));
        runtime.transitionTo(AgentRuntimeStatus.EXECUTING_TOOL);
        var executed = executeTools(turnTools.available(), res.toolCalls(), ctx, run, runtime, state.deadlineNanos);
        for (int index = 0; index < res.toolCalls().size(); index++) {
            String toolResult = budgetToolResult(runtime, executed.results().get(index));
            var call = res.toolCalls().get(index);
            if ("readFile".equals(call.name())
                    && objectMapper.readTree(toolResult).path("bodyOmitted").asBoolean(false)) {
                var path = objectMapper.readTree(call.arguments()).path("path");
                if (path.isString()) {
                    ctx.contextTrace().forgetRead(ctx.resolvePath(path.asString()));
                }
            }
            runtime.messages().add(LlmMessage.toolResult(call.id(), toolResult));
        }
        if (run.getMode() == ProjectType.BUILD && res.toolCalls().stream().anyMatch(call ->
                Set.of("runBuild", "runTests", "runTypeCheck", "validateChanges", "finishBuild").contains(call.name()))) {
            var report = validation.lastReport(run.getId());
            if (report != null && report.outcome() == ValidationCheck.Outcome.FAIL
                    && report.changeSetRevision() == changeSets.view(ctx).revision()) {
                prepareRepair(state, report, routes);
            }
        }
        return run.getMode() == ProjectType.BUILD && executed.allSucceeded()
                && "finishBuild".equals(res.toolCalls().getLast().name());
    }

    /**
     * BUILD completion gates: plan, changed files, final validation, preview entrypoint and the
     * optional review turn. False means a gate sent the model back with a message.
     */
    private boolean passBuildGates(RunState state, BuildGateState gates, LlmResult res, String userRequest,
            ModelRoutes routes, RunSize size) {
        AgentRun run = state.run;
        AgentRuntimeContext runtime = state.runtime;
        AgentContext ctx = state.ctx;
        if (changeSets.view(ctx).touchedPaths().isEmpty()) {
            if (gates.implementationReminders++ > 0) {
                throw new AgentValidationFailedException("The agent did not create or modify any project files");
            }
            runtime.messages().add(LlmMessage.user(PromptLoader.load(Prompt.BUILD_GATE_NO_CHANGES)));
            return false;
        }
        if (changeSets.hasUnvalidatedChanges(ctx)) {
            // The final gate is deterministic. A model does not need an extra turn
            // just to call validation after its last mutation.
            ValidationReport finalReport = validation.validateAll(ctx);
            if (!finalReport.changeValidationPassed() && deterministicFixes != null
                    && !changeSets.validationProgress(ctx).repairLimitExhausted()
                    && deterministicFixes.tryFix(ctx, run, finalReport, () -> {
                        requireBeforeDeadline(state.deadlineNanos);
                        requireNotCancelled(run);
                        requireToolCallsAvailable(runtime, 1, size);
                        if (checkpoints != null && !checkpoints.reserveTools(run, 1)) {
                            throw new AgentLimitExceededException("Durable repair operation limit reached");
                        }
                        runtime.recordToolCalls(1);
                    })) {
                events.emit(run, AgentStepType.STATUS, "Trusted setup repair applied", null);
                finalReport = validation.validateAll(ctx);
            }
            events.emit(run, AgentStepType.STATUS, "Final validation", Map.of(
                    "validationOutcome", finalReport.outcome().name(),
                    "validationPassed", finalReport.changeValidationPassed(),
                    "introducedDiagnosticCount", finalReport.introducedDiagnostics().size(),
                    "repairAttempts", finalReport.repairAttempts()));
            if (!finalReport.changeValidationPassed()) {
                if (changeSets.validationProgress(ctx).repairLimitExhausted()) {
                    throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
                }
                // Finishing again without any successful change cannot pass; stop instead of spending the budget.
                int revision = changeSets.view(ctx).revision();
                gates.unchangedRetries = revision == gates.failedRevision ? gates.unchangedRetries + 1 : 0;
                gates.failedRevision = revision;
                if (gates.unchangedRetries >= MAX_UNCHANGED_FINISH_RETRIES) {
                    throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
                }
                prepareRepair(state, finalReport, routes);
                return false;
            }
        }
        if (!hasPreviewEntrypoint(ctx)) {
            if (gates.previewReminders++ > 0) {
                throw new AgentValidationFailedException(
                        "The build has no browser preview entrypoint; no changes were committed");
            }
            runtime.messages().add(LlmMessage.user(PromptLoader.load(Prompt.BUILD_GATE_ENTRYPOINT)));
            return false;
        }
        if (!gates.visuallyReviewed && state.newProject && visualReview != null
                && runtime.modelCallCount() + 2 <= size.maxModelCalls()
                && runtime.iteration() + 2 <= size.maxIterations()) {
            gates.visuallyReviewed = true;
            var feedback = visualReview.review(ctx, userRequest, request -> visualReviewCall(state, size, request));
            if (!feedback.isEmpty()) {
                String data = objectMapper.writeValueAsString(Map.of("observations", feedback));
                String prompt = PromptLoader.load(Prompt.VISUAL_REFINEMENT).replace("{{observations}}", data);
                if (!runtime.reserveContext(prompt.length())) {
                    throw new AgentLimitExceededException("Visual feedback exceeds context capacity");
                }
                runtime.messages().add(LlmMessage.user(prompt));
                events.emit(run, AgentStepType.STATUS, "Refining the desktop and mobile layout", null);
                return false;
            }
        }
        if (!gates.polished && state.newProject) {
            // Only a new app's first build gets the completeness review; follow-up edits skip this extra call.
            gates.polished = true;
            gates.firstSummary = res.content();
            gates.reviewedRevision = changeSets.view(ctx).revision();
            runtime.messages().add(LlmMessage.assistant(res.content() == null || res.content().isBlank()
                    ? "The first version is built and validated."
                    : res.content()));
            runtime.messages().add(LlmMessage.user(polishPrompt(userRequest)));
            events.emit(run, AgentStepType.STATUS, "Polish check", null);
            return false;
        }
        return true;
    }

    private void prepareRepair(RunState state, ValidationReport report, ModelRoutes routes) {
        state.repairing = true;
        if (state.repairContextRevision == report.changeSetRevision()) return;
        state.repairContextRevision = report.changeSetRevision();
        String signature = report.introducedDiagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR)
                .map(diagnostic -> diagnostic.file() + "|" + diagnostic.code() + "|" + diagnostic.message())
                .sorted().collect(Collectors.joining("\n"));
        if (routes.failedValidation(signature)) {
            events.emit(state.run, AgentStepType.STATUS, "Trying another configured model for repair", null);
        }
        String feedback = RepairFeedback.prompt(report);
        if (featureOutcomes != null && report.checks().stream()
                .anyMatch(check -> check.blocksSuccess() && check.id().startsWith("FEATURE_"))) {
            feedback += "\n" + featureOutcomes.guidance(featureOutcomes.plan(state.run.getProjectId()));
        }
        if (!state.runtime.reserveContext(feedback.length())) return;
        state.runtime.messages().add(LlmMessage.user(feedback));
        int remaining = state.runtime.remainingContextChars() - PRELOAD_HEADROOM_CHARS;
        if (remaining <= 0) return;
        List<String> targets = report.introducedDiagnostics().stream()
                .filter(diagnostic -> diagnostic.severity() == DiagnosticSeverity.ERROR && diagnostic.file() != null)
                .map(ProjectDiagnostic::file).distinct().toList();
        if (targets.isEmpty()) targets = changeSets.view(state.ctx).touchedPaths().stream().sorted().toList();
        var current = projectContexts.preloadedFiles(state.ctx, "", targets, remaining);
        if (current.text().isBlank() || !state.runtime.reserveContext(current.text().length())) return;
        state.runtime.messages().add(LlmMessage.user(current.text()));
        current.versions().forEach((path, version) -> {
            long bytes = files.size(state.run.getProjectId(), state.ctx.root(), path);
            state.ctx.contextTrace().recordRead(path, bytes, version, (int) Math.min(Integer.MAX_VALUE, bytes));
        });
    }

    private LlmResult visualReviewCall(RunState state, RunSize size, LlmRequest request) {
        requireNotCancelled(state.run);
        requireBeforeDeadline(state.deadlineNanos);
        var selection = new AgentProperties.ModelSelection(request.provider(), request.model());
        reserveModelCall(state, size, selection);
        Duration remaining = callBudget(state.deadlineNanos);
        Duration budget = request.budget().compareTo(remaining) < 0 ? request.budget() : remaining;
        var bounded = new LlmRequest(request.provider(), request.model(), request.messages(), request.tools(),
                request.temperature(), budget, null, Math.min(request.maxTokens(), props.getLlm().getMaxOutputTokens()));
        try (var purpose = LlmCallContext.purpose(CallPurpose.VISUAL_REVIEW, true)) {
            var response = llm.chat(bounded);
            accountTokens(state.run, response);
            return response;
        } catch (LlmCancelledException | LlmQuotaExceededException | LlmAccountingException terminal) {
            throw terminal;
        } catch (LlmException unavailable) {
            log.info("visual_review_unavailable runId={} errorType={}", state.run.getId(), unavailable.getClass().getSimpleName());
            return null;
        }
    }

    /** Saves the staged build and prepares its preview; the reply describes the saved change. */
    private void commitBuild(AgentRun run, AgentContext ctx, ReplyStream stream, String summary,
            String noChangesMessage) {
        ChangeSetService.CommitResult committed = changeSets.commit(ctx, run);
        if (committed.changedPaths().isEmpty()) {
            throw new AgentValidationFailedException(noChangesMessage);
        }
        events.emit(run, AgentStepType.STATUS, "Change set committed",
                Map.of("changedFiles", committed.changedPaths(), "snapshotRef", committed.snapshotRef()));
        if (artifacts != null) {
            artifacts.writeBuild(run, "COMMITTED", committed.changedPaths());
        }
        if (databases != null) {
            databases.ensure(run.getProjectId());
        }
        buildSite(run);
        moveProject(run, ProjectStage.REVIEW);
        stream.append(BuildReplyText.success(summary, run)
                + FailureExplanation.unconfirmedBehaviour(validation == null ? null
                        : validation.lastReport(run.getId())));
    }

    private AgentRunResult completeRun(RunState state) {
        AgentRun run = state.run;
        AgentRuntimeContext runtime = state.runtime;
        runtime.transitionPhase(AgentPhase.FINISH);
        runtime.transitionTo(AgentRuntimeStatus.COMPLETED);
        // Run DONE before the reply shows as complete: a user who answers the moment it
        // appears must not be told a request is still in progress.
        setStatus(run, RunStatus.DONE);
        state.stream.complete();
        notifyBuild(run, true);
        events.emit(run, AgentStepType.STATUS, "Agent completed", counters(runtime));
        emitContextUsage(run, state.projectContext, runtime);
        log.info("agent_completed runId={} iterations={} modelCalls={} toolCalls={}", run.getId(),
                runtime.iteration(), runtime.modelCallCount(), runtime.toolCallCount());
        return result(run, runtime, state.stream.content(), null);
    }

    private AgentRunResult saveRecoveryPending(RunState state, PromotionRecoveryPendingException pending) {
        AgentRun run = state.run;
        events.emit(run, AgentStepType.STATUS, "Save recovery pending", null);
        try {
            runsFlagRecovery(run);
        } catch (RuntimeException unavailable) {
            log.warn("save_recovery_status_deferred runId={}", run.getId());
        }
        state.cancellation.cancel();
        run.setError(pending.getMessage());
        return result(run, state.runtime, "Code save needs recovery verification; project writes are paused.",
                run.getError());
    }

    /** Shared terminal bookkeeping for cancelled and failed runs. */
    private void markEnded(RunState state, AgentRuntimeStatus runtimeStatus, RunStatus runStatus,
            String artifactState) {
        AgentRun run = state.run;
        if (state.runtime != null) {
            state.runtime.transitionTo(runtimeStatus);
        }
        setStatus(run, runStatus);
        moveProject(run, ProjectStage.FAILED);
        if (artifacts != null) {
            artifacts.writeBuild(run, artifactState, List.of());
        }
    }

    private AgentRunResult cancelled(RunState state) {
        AgentRun run = state.run;
        if (state.cancellation.isOwnershipLost()) {
            return ownershipLost(run, state.runtime, state.cancellation);
        }
        log.info("agent_cancelled runId={}", run.getId());
        run.setError("Cancelled by user");
        markEnded(state, AgentRuntimeStatus.CANCELLED, RunStatus.CANCELLED, "CANCELLED");
        updateAssistant(run, "Run cancelled.", MessageStatus.FAILED);
        events.emit(run, AgentStepType.STATUS, "Agent cancelled", safeCounters(state.runtime));
        emitContextUsage(run, state.projectContext, state.runtime);
        return result(run, state.runtime, "Run cancelled.", run.getError());
    }

    private AgentRunResult quotaExceeded(RunState state, RuntimeException e) {
        AgentRun run = state.run;
        log.info("agent_quota_exceeded runId={} userId={}", run.getId(), run.getUserId());
        String message = run.getMode() == ProjectType.BUILD
                ? e.getMessage() + " The build was stopped before any change was committed."
                : e.getMessage();
        run.setError(message);
        boolean runBudget = e instanceof LlmQuotaExceededException quota && quota.runBudget();
        run.setFailureReason(
                runBudget ? AgentFailureReason.RUN_TOKEN_BUDGET_EXCEEDED : AgentFailureReason.DAILY_QUOTA_EXCEEDED);
        markEnded(state, AgentRuntimeStatus.FAILED, RunStatus.FAILED, "FAILED");
        updateAssistant(run, message, MessageStatus.FAILED);
        events.emit(run, AgentStepType.STATUS,
                runBudget ? "Agent stopped: run budget" : "Agent stopped: daily quota", Map.of(
                        "status", "FAILED", "reason",
                        runBudget ? "RUN_TOKEN_BUDGET_EXCEEDED" : "DAILY_QUOTA_EXCEEDED", "message", message));
        emitContextUsage(run, state.projectContext, state.runtime);
        return result(run, state.runtime, message, message);
    }

    private AgentRunResult validationFailed(RunState state, AgentValidationFailedException e) {
        AgentRun run = state.run;
        log.info("agent_validation_failed runId={}", run.getId());
        run.setError(e.getMessage());
        run.setFailureReason(AgentFailureReason.BUILD_VALIDATION_FAILED);
        markEnded(state, AgentRuntimeStatus.FAILED, RunStatus.FAILED, "FAILED");
        notifyBuild(run, false);
        String validationMessage = FailureExplanation.validation(validation == null ? null : validation.lastReport(run.getId()));
        updateAssistant(run, validationMessage, MessageStatus.FAILED);
        events.emit(run, AgentStepType.STATUS, "Agent validation failed", Map.of(
                "status", "FAILED", "reason", "BUILD_GATE_FAILED", "message", e.getMessage()));
        emitContextUsage(run, state.projectContext, state.runtime);
        return result(run, state.runtime, validationMessage, e.getMessage());
    }

    private AgentRunResult failed(RunState state, Exception e) {
        AgentRun run = state.run;
        log.warn("agent_failed runId={} errorType={} message={}", run.getId(), e.getClass().getSimpleName(),
                e.getMessage());
        run.setError(safeError(e));
        run.setFailureReason(e instanceof LlmHttpException rejected && rejected.terminalRequest()
                ? AgentFailureReason.PROVIDER_REQUEST_REJECTED
                : e instanceof AgentLimitExceededException ? AgentFailureReason.RUN_LIMIT_REACHED
                        : e instanceof WorkflowStageException ? AgentFailureReason.USER_REVIEW_REQUIRED
                                : AgentFailureReason.AGENT_FAILED);
        markEnded(state, AgentRuntimeStatus.FAILED, RunStatus.FAILED, "FAILED");
        notifyBuild(run, false);
        String safeFailure = safeError(e);
        ValidationReport lastFailedReport = e instanceof WorkflowStageException && "Repair limit reached".equals(safeFailure)
                && validation != null ? validation.lastReport(run.getId()) : null;
        String failureMessage = FailureExplanation.failure(run.getFailureReason(), run.getCodeSavedAt() != null,
                safeFailure, lastFailedReport);
        if (run.getCodeSavedAt() != null) {
            recordPreview(run, "FAILED");
        }
        updateAssistant(run, failureMessage, MessageStatus.FAILED);
        events.emit(run, AgentStepType.STATUS, "Agent failed", safeCounters(state.runtime));
        emitContextUsage(run, state.projectContext, state.runtime);
        return result(run, state.runtime, failureMessage, run.getError());
    }

    private void cleanup(RunState state) {
        AgentRun run = state.run;
        if (cancellations != null) {
            cancellations.unregister(run.getId());
        }
        if (drafts != null) {
            drafts.finish(run.getId());
        }
        changeSets.finish(run);
        validation.clear(run.getId());
        if (visualReview != null) {
            visualReview.clear(run.getId());
        }
        stopQuietly(state.beat);
        try {
            queue.clearCancel(run.getId());
        } catch (RuntimeException unavailable) {
            log.warn("Redis cancel cleanup deferred runId={} errorType={}",
                    run.getId(), unavailable.getClass().getSimpleName());
        }
    }

    private void runsFlagRecovery(AgentRun run) {
        runRepository.flagSaveRecovery(run.getId());
        run.setSaveRecoveryRequired(true);
    }

    private static final int CONVERSATIONAL_HISTORY_TURNS = 6;
    /** Chat models tried for one small-talk reply before it fails. */
    private static final int CONVERSATIONAL_MODEL_ATTEMPTS = 3;

    /** One no-tools reply. Only unclear turns need the compact project context and a structured decision. */
    private AgentRunResult runConversational(AgentRun run, ReplyStream stream, List<Message> history,
            String userRequest, long deadlineNanos, boolean unclear, List<String> attachments) {
        projects.requireAccessible(run.getUserId(), run.getProjectId());
        List<LlmMessage> convo = new ArrayList<>();
        convo.add(LlmMessage.system(PromptLoader.load(Prompt.CONVERSATIONAL)));
        if (unclear) {
            convo.add(LlmMessage.system(UnclearReply.POLICY));
            convo.add(LlmMessage.user(projectContexts.compactSummary(files, run.getProjectId(), userRequest)));
        }
        if (!attachments.isEmpty() && vision != null) {
            events.emit(run, AgentStepType.STATUS, "Reading attachments", Map.of("files", attachments));
            convo.add(LlmMessage
                    .user(VisionService.asContext(vision.describe(run.getProjectId(), attachments, userRequest))));
        } else if (!attachments.isEmpty()) {
            convo.add(LlmMessage.user("Uploaded context references (contents not inspected): " + attachments));
        }
        List<Message> contextHistory = history.stream()
                .filter(message -> message.getRole() != MessageRole.SYSTEM)
                .toList();
        int lastUserIdx = -1;
        for (int i = contextHistory.size() - 1; i >= 0; i--) {
            if (contextHistory.get(i).getRole() == MessageRole.USER) {
                lastUserIdx = i;
                break;
            }
        }
        List<Message> withoutCurrent = lastUserIdx >= 0
                ? contextHistory.subList(0, lastUserIdx)
                : contextHistory;
        withoutCurrent.stream()
                .skip(Math.max(0, withoutCurrent.size() - CONVERSATIONAL_HISTORY_TURNS))
                .map(message -> message.getRole() == MessageRole.ASSISTANT
                        ? LlmMessage.assistant(message.getContent())
                        : LlmMessage.user(message.getContent()))
                .forEach(convo::add);
        convo.add(LlmMessage.user(userRequest));

        LlmResult res = null;
        StringBuilder decisionText = new StringBuilder();
        ModelRoutes routes = new ModelRoutes(props, ProjectType.CHAT);
        try (LlmCallContext.Scope smallTalk = LlmCallContext.purpose(CallPurpose.SMALL_TALK, true)) {
            // Small talk gets the same fallback as the main loop: a provider that is down or out of
            // budget hands over to the next configured chat model instead of failing the reply.
            for (int attempt = 1; attempt <= CONVERSATIONAL_MODEL_ATTEMPTS && res == null; attempt++) {
                requireBeforeDeadline(deadlineNanos);
                requireNotCancelled(run);
                requireWithinDailyQuota(run);
                AgentProperties.ModelSelection selection = routes.next();
                log.info("model_called runId={} conversational=true attempt={} provider={} model={}", run.getId(),
                        attempt, selection.provider(), selection.model());
                try {
                    res = llm.chatStreaming(new LlmRequest(selection.provider(), selection.model(), convo, List.of(),
                            props.getLlm().getTemperature(), callBudget(deadlineNanos))
                            .withMaxTokens(props.getLlm().getSmallTalkMaxOutputTokens()),
                            unclear ? decisionText::append : stream::append);
                    props.markModelAvailable(selection);
                } catch (LlmException providerFailure) {
                    providerFailed(run, routes, selection, providerFailure, true);
                }
            }
            if (res == null) {
                throw routes.lastFailure();
            }
            if (unclear) {
                var decision = UnclearReply.decode(objectMapper,
                        res.content() == null ? decisionText.toString() : res.content(), userRequest);
                if (decision.action().equals("build")) {
                    // The model's reply only describes the offer; accepting it builds what the user actually asked.
                    suggestBuild(run, userRequest, attachments);
                }
                stream.append(decision.reply());
            }
            stream.flush();
            accountTokens(run, res);
        }
        if (res.reasoning() != null && !res.reasoning().isBlank()) {
            events.emit(run, AgentStepType.THINKING, "Model reasoning", Map.of("reasoning", res.reasoning()));
        }
        run.setConversational(true);
        setStatus(run, RunStatus.DONE); // before the reply completes; see the main loop
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
        if (leases != null)
            leases.checkCurrent(run.getProjectId());
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

    private record ExecutedTools(List<String> results, boolean allSucceeded) {
    }

    private ExecutedTools executeTools(List<AgentTool> available, List<ToolCall> calls, AgentContext ctx,
            AgentRun run, AgentRuntimeContext runtime, long deadlineNanos) {
        Set<String> allowedNames = available.stream().map(AgentTool::name).collect(Collectors.toSet());
        List<PreparedToolCall> prepared = calls.stream()
                .map(call -> new PreparedToolCall(call, parseArgs(call.arguments()), findTool(available, call.name())))
                .toList();
        prepared.forEach(item -> {
            log.info("tool_requested runId={} tool={}", run.getId(), item.call.name());
            events.emit(run, AgentStepType.TOOL_CALL, item.call.name(), ToolAudit.arguments(item.args));
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
            if (result.success() || result.error() == null) {
                log.info("tool_{} runId={} tool={}", result.success() ? "completed" : "failed", run.getId(), item.call.name());
            } else {
                String message = result.error().message() == null ? "" : result.error().message();
                log.info("tool_failed runId={} tool={} code={} message={}", run.getId(), item.call.name(), result.error().code(),
                        message.length() > 200 ? message.substring(0, 200) + "..." : message);
            }
            events.emit(run, AgentStepType.TOOL_RESULT, item.call.name(), ToolAudit.result(result, item.args));
        }
        boolean wrote = false;
        for (int index = 0; index < prepared.size(); index++) {
            wrote |= results.get(index).success() && FILE_CHANGING_TOOLS.contains(prepared.get(index).call.name());
        }
        if (wrote && drafts != null && ctx.mode() == ProjectType.BUILD)
            drafts.changed(run.getProjectId(), run.getId(), ctx.root());
        return new ExecutedTools(results.stream().map(this::toModelToolResult).toList(),
                results.stream().allMatch(result -> result.success()));
    }

    /** The review turn: the request again, and the quality checklist from the build prompt as questions. */
    static String polishPrompt(String userRequest) {
        return PromptLoader.load(Prompt.POLISH).replace("{{request}}", userRequest == null ? "" : userRequest.strip());
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
    private Duration callBudget(long deadlineNanos) {
        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(1_000, props.getLlm().getCallBudgetMs()));
        return Duration.ofNanos(Math.min(remaining, budget));
    }

    /** Routes around a failed model: briefly for a passing failure, for long when the account cannot use it. */
    private void markUnavailable(AgentProperties.ModelSelection selection, LlmException failure) {
        if (failure instanceof LlmHttpException http && http.modelUnavailable()) {
            props.markModelUnavailable(selection, props.getLlm().getModelUnavailableCooldownMs());
            log.warn("model_unavailable_for_account provider={} model={} status={}", selection.provider(),
                    selection.model(), http.status());
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

    private void requireWithinDailyQuota(AgentRun run) {
        if (usage == null)
            return;
        long quota = usage.dailyTokenQuota();
        long used = usage.tokensUsedToday(run.getUserId());
        if (quota > 0 && used >= quota)
            throw new AgentQuotaExceededException(AgentUsagePolicy.QUOTA_MESSAGE);
    }

    private static final class AgentValidationFailedException extends RuntimeException {
        private AgentValidationFailedException(String message) {
            super(message);
        }
    }

    private String validationFailureMessage(UUID runId) {
        ValidationReport report = validation.lastReport(runId);
        if (report == null) {
            return "The staged changes were not successfully validated; no changes were committed.";
        }
        if (report.outcome() == ValidationCheck.Outcome.SKIPPED)
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
        }).collect(Collectors.joining("\n"));
        return "Validation still failed after the configured repair limit. No staged changes were committed.\n"
                + details;
    }

    /** Refreshes the served site; a failed build keeps the previous site and does not fail the run. */
    private void buildSite(AgentRun run) {
        if (sites == null)
            return;
        recordPreview(run, "BUILDING");
        SiteBuildService.Result site;
        try {
            site = sites.build(run.getProjectId());
        } catch (RuntimeException failed) {
            recordPreview(run, "FAILED");
            throw failed;
        }
        recordPreview(run, site.error() == null ? "READY" : "FAILED");
        if (site.built())
            events.emit(run, AgentStepType.STATUS, "Preview built", null);
        else if (site.error() != null)
            events.emit(run, AgentStepType.STATUS, "Preview build failed", Map.of("error", site.error()));
    }

    private void recordPreview(AgentRun run, String state) {
        owned(run, () -> runRepository.recordPreviewStatus(run.getId(), state));
        run.setPreviewStatus(state);
    }

    private static boolean hasPreviewEntrypoint(AgentContext context) {
        return Stream.of(
                "index.html", "client/index.html", "client/public/index.html", "frontend/index.html",
                "dist/index.html", "build/index.html", "client/dist/index.html", "client/build/index.html",
                "frontend/dist/index.html", "frontend/build/index.html")
                .anyMatch(path -> context.files().regularFile(context.projectId(), context.root(), path));
    }

    private record PreparedToolCall(ToolCall call, Map<String, Object> args, AgentTool tool) {
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
        return ToolAccessPolicy.forMode(tools.all(), mode);
    }

    private String toModelToolResult(ToolExecutionResult result) {
        String json = objectMapper.writeValueAsString(result);
        if (json.length() <= TOOL_RESULT_MAX)
            return json;
        return new ToolResultMemory(objectMapper).compact(json, null, "Tool body exceeded per-result context limit");
    }

    String budgetToolResult(AgentRuntimeContext runtime, String result) {
        if (runtime.reserveContext(result.length()))
            return result;
        String exhausted = new ToolResultMemory(objectMapper).compact(result, null, "Context budget omitted tool body");
        if (!runtime.reserveContext(exhausted.length()))
            throw new AgentLimitExceededException(
                    "Context capacity cannot preserve the tool outcome; no further model call was sent");
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
        if (!props.isSizeTiers())
            return RunSize.of(SizeTier.S, List.of("size tiers off"), props);
        Set<String> instructions = Set.copyOf(context.projectMap().instructionFiles());
        boolean existing = context.projectMap().importantFiles().stream().map(ProjectFileSummary::path)
                .anyMatch(path -> !instructions.contains(path) && !path.startsWith("uploads/"));
        var choice = buildDecisions == null || run.getMode() != ProjectType.BUILD ? null
                : buildDecisions.state(run.getProjectId()).choice();
        SizeTierRules.Decision decision = SizeTierRules.decide(run.getMode(), userRequest, project.getInitialPrompt(),
                existing, choice);
        RunSize size = RunSize.of(decision.tier(), decision.reasons(), props);
        // A new project's first build also installs its dependencies, which takes minutes on a slow registry.
        return run.getMode() == ProjectType.BUILD && !existing
                ? size.withLongerTimeout(props.getLargeRunTimeoutMs(), "first build installs dependencies")
                : size;
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
        Map<String, Object> audit = new LinkedHashMap<>();
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

    private String systemPrompt(AgentRun run, String root) {
        String base = PromptLoader.load(Prompt.SYSTEM_BASE);
        String modePrompt = switch (run.getMode()) {
        case BUILD -> BuildPrompt.compose(BuildPrompt.stack(projectShape(run.getProjectId(), root), approvedStorage(run)));
        case CHAT -> PromptLoader.load(Prompt.MODE_CHAT);
        case PLAN -> PromptLoader.load(Prompt.MODE_PLAN);
        };
        return base + "\n\n" + modePrompt;
    }

    private BuildPrompt.ProjectShape projectShape(UUID projectId, String root) {
        return new BuildPrompt.ProjectShape(files.exists(projectId, root, "server/package.json"), validStaticSite(projectId, root),
                files.exists(projectId, root, "package.json"), files.exists(projectId, root, "index.html"));
    }

    /** Only a marker the server can validate makes a package.json project static; a tampered one does not. */
    private boolean validStaticSite(UUID projectId, String root) {
        if (!files.exists(projectId, root, StaticAssets.MARKER)) {
            return false;
        }
        try {
            StaticAssets.require(files, projectId, root);
            return true;
        } catch (RuntimeException tampered) {
            return false;
        }
    }

    private BuildDecisions.Storage approvedStorage(AgentRun run) {
        if (buildDecisions == null) {
            return null;
        }
        var choice = buildDecisions.state(run.getProjectId()).choice();
        return choice == null ? null : choice.storage();
    }

    private void setStatus(AgentRun run, RunStatus status) {
        run.setStatus(status);
        owned(run, () -> runRepository.save(run));
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
            owned(run, () -> projects.advanceLifecycle(run.getUserId(), run.getProjectId(), stage));
        } catch (LeaseLostException lost) {
            throw lost;
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
        owned(run, () -> runRepository.addReportedTokens(run.getId(), result.usage().promptTokens(),
                result.usage().completionTokens()));
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
        owned(run, () -> {
            messageRepository.findById(run.getMessageId()).ifPresent(message -> {
                message.setContent(content);
                message.setStatus(status);
                Message saved = messageRepository.save(message);
                var frame = RealtimeMessage.of("MESSAGE_UPSERT", MessageResponse.from(saved).withoutSuggestedAction());
                Runnable broadcast = () -> realtime.sendToProject(run.getProjectId(), frame);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            broadcast.run();
                        }
                    });
                } else {
                    broadcast.run();
                }
            });
            return null;
        });
    }

    private ReplyStream replyStream(AgentRun run) {
        return new ReplyStream(props, () -> {
            if (run.getRespondingAt() == null) {
                run.setRespondingAt(Instant.now());
                owned(run, () -> runRepository.save(run));
            }
        }, delta -> realtime.sendToProject(run.getProjectId(), RealtimeMessage.of("MESSAGE_DELTA", Map.of(
                "id", run.getMessageId(), "projectId", run.getProjectId().toString(), "delta", delta))),
                content -> messageRepository.findById(run.getMessageId()).ifPresent(message -> {
                    message.setContent(content);
                    message.setStatus(MessageStatus.STREAMING);
                    messageRepository.save(message);
                }), content -> updateAssistant(run, content, MessageStatus.COMPLETE));
    }

    private String safeError(Exception e) {
        String message = e.getMessage() == null ? "Agent run failed" : e.getMessage();
        return message.length() <= 1_000 ? message : message.substring(0, 1_000);
    }

}
