package com.code.pal.agent;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.context.ContextDiagnostics;
import com.code.pal.agent.context.ContextMessage;
import com.code.pal.agent.context.ProjectContext;
import com.code.pal.agent.context.ProjectContextService;
import com.code.pal.agent.domain.AgentIntent;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.domain.AgentStepType;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.intent.IntentClassifier;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.llm.LlmClient.LlmRequest;
import com.code.pal.agent.llm.LlmClient.LlmResult;
import com.code.pal.agent.llm.LlmClient.ToolCall;
import com.code.pal.agent.llm.LlmClient.ToolSpec;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.AgentRunResult;
import com.code.pal.agent.runtime.AgentRuntime;
import com.code.pal.agent.runtime.AgentRuntimeContext;
import com.code.pal.agent.runtime.AgentRuntimeStatus;
import com.code.pal.agent.runtime.AgentPhase;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentProjectMetadata;
import com.code.pal.agent.tool.AgentTool;
import com.code.pal.agent.tool.AgentToolExecutor;
import com.code.pal.agent.tool.ToolRegistry;
import com.code.pal.agent.tool.ToolExecutionResult;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.DockerProjectEnvironment;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
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
            "listFiles", "readFile", "searchCode", "getProjectMetadata", "getFileSummary");
    private static final Set<String> PLANNING_TOOLS = Set.of("setPlan", "updatePlanStep");
    private static final Set<String> WRITE_TOOLS = Set.of("createFile", "createFiles", "patchFile", "deleteFile",
            "renameFile",
            "getDiagnostics", "runTypeCheck", "runBuild", "runTests", "validateChanges");

    private final LlmClient llm;
    private final IntentClassifier intentClassifier;
    private final ToolRegistry tools;
    private final AgentToolExecutor toolExecutor;
    private final AgentEvents events;
    private final AgentProperties props;
    private final ProjectContextService projectContexts;
    private final AgentRunRepository runRepository;
    private final ProjectService projects;
    private final MessageRepository messageRepository;
    private final RealtimeService realtime;
    private final WorkspaceStorage workspace;
    private final DockerProjectEnvironment sandbox;
    private final AgentHeartbeat heartbeat;
    private final AgentJobQueue queue;
    private final ObjectMapper objectMapper;
    private final ChangeSetService changeSets;
    private final ProjectValidationService validation;
    private final NotificationService notifications;
    private final ProjectArtifactService artifacts;
    private final GeneratedDatabaseService databases;

    @Autowired
    public AgentRunner(LlmClient llm, IntentClassifier intentClassifier, ToolRegistry tools,
            AgentToolExecutor toolExecutor, AgentEvents events, AgentProperties props,
            ProjectContextService projectContexts, AgentRunRepository runRepository,
            ProjectService projects, MessageRepository messageRepository, RealtimeService realtime,
            WorkspaceStorage workspace, AgentHeartbeat heartbeat, AgentJobQueue queue,
            ObjectMapper objectMapper, ChangeSetService changeSets,
            ProjectValidationService validation, NotificationService notifications,
            ProjectArtifactService artifacts, GeneratedDatabaseService databases,
            DockerProjectEnvironment sandbox) {
        this.llm = llm;
        this.intentClassifier = intentClassifier;
        this.tools = tools;
        this.toolExecutor = toolExecutor;
        this.events = events;
        this.props = props;
        this.projectContexts = projectContexts;
        this.runRepository = runRepository;
        this.projects = projects;
        this.messageRepository = messageRepository;
        this.realtime = realtime;
        this.workspace = workspace;
        this.sandbox = sandbox;
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
    public AgentRunner(LlmClient llm, IntentClassifier intentClassifier, ToolRegistry tools,
            AgentToolExecutor toolExecutor, AgentEvents events, AgentProperties props,
            ProjectContextService projectContexts, AgentRunRepository runRepository,
            ProjectService projects, MessageRepository messageRepository, RealtimeService realtime,
            WorkspaceStorage workspace, AgentHeartbeat heartbeat, AgentJobQueue queue,
            ObjectMapper objectMapper, ChangeSetService changeSets,
            ProjectValidationService validation, NotificationService notifications) {
        this(llm, intentClassifier, tools, toolExecutor, events, props, projectContexts, runRepository, projects,
                messageRepository, realtime, workspace, heartbeat, queue, objectMapper, changeSets, validation,
                notifications, null, null, new DockerProjectEnvironment(props));
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
        try {
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(props.getRunTimeoutMs());
            run.setHeartbeatAt(Instant.now());
            setStatus(run, RunStatus.RUNNING);
            log.info("agent_started runId={} projectId={} userId={}", run.getId(), run.getProjectId(), run.getUserId());
            events.emit(run, AgentStepType.STATUS, "Agent started", Map.of("status", "RUNNING"));
            beat = heartbeat.start(run.getId(), run.getProjectId());
            updateAssistant(run, "", MessageStatus.STREAMING);
            requireNotCancelled(run); // cancelled while still queued — stop before any work

            List<Message> history = loadHistory(run);
            String userRequest = currentUserRequest(history);
            if (intentClassifier.classify(userRequest) == AgentIntent.CONVERSATIONAL) {
                return runConversational(run, stream, history, userRequest, deadlineNanos);
            }

            Project project = run.getMode() == ProjectType.BUILD
                    ? projects.requireManageable(run.getUserId(), run.getProjectId())
                    : projects.requireAccessible(run.getUserId(), run.getProjectId());
            if (run.getMode() == ProjectType.BUILD)
                moveProject(run, ProjectStage.BUILDING);
            boolean dockerWorkspace = run.getMode() == ProjectType.BUILD && props.getSandbox().isRequired();
            Path liveWorkspace = dockerWorkspace ? Path.of("/workspace") : workspace.ensure(run.getProjectId());
            Path ws = run.getMode() == ProjectType.BUILD
                    ? (dockerWorkspace ? changeSets.start(run.getId(), run.getProjectId())
                            : changeSets.start(run.getId(), run.getProjectId(), liveWorkspace))
                    : liveWorkspace;
            projectContext = dockerWorkspace
                    ? projectContexts.buildSandbox(sandbox, run.getProjectId(),
                            ".codepal/changesets/" + run.getId() + "/workspace",
                            userRequest, history, List.of())
                    : projectContexts.build(ws, userRequest, history, List.of());
            AgentPlanState plan = new AgentPlanState();
            ctx = new AgentContext(run.getProjectId(), run.getUserId(), run.getMode(), ws,
                    AgentProjectMetadata.from(project), projectContext.trace(), run.getId(), plan,
                    dockerWorkspace ? sandbox : null,
                    dockerWorkspace ? ".codepal/changesets/" + run.getId() + "/workspace" : null);
            List<AgentTool> available = toolsForRun(run.getMode());
            List<ToolSpec> specs = available.stream()
                    .map(t -> new ToolSpec(t.name(), t.description(), t.parametersSchema())).toList();

            List<LlmMessage> convo = new ArrayList<>();
            convo.add(LlmMessage.system(systemPrompt(run.getMode())));
            if (!projectContext.projectContextMessage().isBlank()) {
                convo.add(LlmMessage.user(projectContext.projectContextMessage()));
            }
            projectContext.conversation().stream().map(AgentRunner::toLlmMessage).forEach(convo::add);
            int initialContextChars = convo.stream()
                    .mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum()
                    + specs.toString().length();
            runtime = new AgentRuntimeContext(run.getId(), run.getProjectId(), run.getUserId(), userRequest, convo,
                    props.getContextBudgetChars(), initialContextChars, plan);
            runtime.transitionTo(AgentRuntimeStatus.RUNNING);
            runtime.transitionPhase(AgentPhase.EXPLORE);
            events.emit(run, AgentStepType.STATUS, "Project context prepared",
                    contextAudit(projectContext.diagnostics()));

            int planningReminders = 0;
            int implementationReminders = 0;
            int previewReminders = 0;
            for (int i = 0; i < props.getMaxIterations(); i++) {
                requireBeforeDeadline(deadlineNanos);
                requireNotCancelled(run);
                runtime.beginIteration();
                requireModelCallAvailable(runtime);
                runtime.transitionTo(AgentRuntimeStatus.WAITING_FOR_MODEL);
                runtime.recordModelCall();
                boolean finalRepairReportCall = run.getMode() == ProjectType.BUILD
                        && changeSets.validationProgress(ctx).repairLimitExhausted();
                AgentProperties.ModelSelection selection = props.modelFor(run.getMode());
                log.info("model_called runId={} iteration={} modelCall={} provider={} model={}", run.getId(),
                        runtime.iteration(),
                        runtime.modelCallCount(), selection.provider(), selection.model());
                events.emit(run, AgentStepType.STATUS, "Model called", Map.of(
                        "iteration", runtime.iteration(), "modelCall", runtime.modelCallCount()));
                LlmResult res;
                try {
                    res = llm.chatStreaming(new LlmRequest(
                            selection.provider(), selection.model(), runtime.messages(), specs,
                            props.getLlm().getTemperature()), stream::append);
                } catch (LlmException providerFailure) {
                    // Keep the conversation intact when a provider rejects a request before it
                    // publishes output. The next round-robin model can support a different tool
                    // schema or have independent rate limits; the model-call cap still bounds cost.
                    if (providerFailure.getMessage() != null
                            && providerFailure.getMessage().contains("after partial output")) {
                        throw providerFailure;
                    }
                    log.warn(
                            "llm_provider_unavailable runId={} provider={} model={} reason={}; trying next configured model",
                            run.getId(), selection.provider(), selection.model(), providerFailure.getMessage());
                    events.emit(run, AgentStepType.STATUS, "LLM provider unavailable", Map.of(
                            "provider", selection.provider(), "model", selection.model(),
                            "retryable", providerFailure instanceof com.code.pal.agent.llm.RetryableLlmException));
                    continue;
                }
                stream.flush();
                accountTokens(run, res);

                boolean hasReasoning = res.reasoning() != null && !res.reasoning().isBlank();
                if (hasReasoning || (res.hasToolCalls() && res.content() != null && !res.content().isBlank())) {
                    events.emit(run, AgentStepType.THINKING, "Model reasoning", hasReasoning
                            ? Map.of("reasoning", res.reasoning())
                            : Map.of("toolCallCount", res.toolCalls().size()));
                }

                if (finalRepairReportCall) {
                    throw new AgentValidationFailedException(validationFailureMessage(run.getId()));
                }

                if (!res.hasToolCalls()) {
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
                        ChangeSetService.CommitResult committed = changeSets.commit(ctx, run);
                        if (committed.changedPaths().isEmpty()) {
                            throw new AgentValidationFailedException(
                                    "The staged build produced no committed file changes");
                        }
                        stream.append("\n\nChanged files:\n" + committed.changedPaths().stream()
                                .map(path -> "- " + path).collect(java.util.stream.Collectors.joining("\n")));
                        events.emit(run, AgentStepType.STATUS, "Change set committed", Map.of(
                                "changedFiles", committed.changedPaths(), "snapshotRef", committed.snapshotRef()));
                        if (artifacts != null)
                            artifacts.writeBuild(run, "COMMITTED", committed.changedPaths());
                        if (databases != null)
                            databases.ensure(run.getProjectId());
                        moveProject(run, ProjectStage.REVIEW);
                    }
                    runtime.transitionPhase(AgentPhase.FINISH);
                    stream.complete();
                    runtime.transitionTo(AgentRuntimeStatus.COMPLETED);
                    setStatus(run, RunStatus.DONE);
                    notifyBuild(run, true);
                    events.emit(run, AgentStepType.STATUS, "Agent completed", counters(runtime));
                    emitContextUsage(run, projectContext, runtime);
                    log.info("agent_completed runId={} iterations={} modelCalls={} toolCalls={}", run.getId(),
                            runtime.iteration(), runtime.modelCallCount(), runtime.toolCallCount());
                    return result(run, runtime, stream.content(), null);
                }

                requireToolCallsAvailable(runtime, res.toolCalls().size());
                runtime.recordToolCalls(res.toolCalls().size());
                String assistantContext = budgetAssistantContext(runtime, res.content());
                runtime.messages().add(new LlmMessage("assistant", assistantContext, res.toolCalls(), null));
                runtime.transitionTo(AgentRuntimeStatus.EXECUTING_TOOL);
                List<String> results = executeTools(available, res.toolCalls(), ctx, run, runtime, deadlineNanos);
                for (int toolIndex = 0; toolIndex < res.toolCalls().size(); toolIndex++) {
                    String toolResult = budgetToolResult(runtime, results.get(toolIndex));
                    runtime.messages().add(LlmMessage.toolResult(res.toolCalls().get(toolIndex).id(), toolResult));
                }
            }

            throw new AgentLimitExceededException("Agent iteration limit reached");
        } catch (AgentCancelledException e) {
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
        } catch (AgentValidationFailedException e) {
            log.info("agent_validation_failed runId={}", run.getId());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.FAILED);
            run.setError(e.getMessage());
            setStatus(run, RunStatus.FAILED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "FAILED", List.of());
            notifyBuild(run, false);
            updateAssistant(run, e.getMessage(), MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, "Agent validation failed", Map.of(
                    "status", "FAILED", "reason", "BUILD_GATE_FAILED", "message", e.getMessage()));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, e.getMessage(), e.getMessage());
        } catch (Exception e) {
            log.warn("agent_failed runId={} errorType={} message={}", run.getId(), e.getClass().getSimpleName(), e.getMessage());
            if (runtime != null)
                runtime.transitionTo(AgentRuntimeStatus.FAILED);
            run.setError(safeError(e));
            setStatus(run, RunStatus.FAILED);
            moveProject(run, ProjectStage.FAILED);
            if (artifacts != null)
                artifacts.writeBuild(run, "FAILED", List.of());
            notifyBuild(run, false);
            updateAssistant(run, "Something went wrong while running the agent.", MessageStatus.FAILED);
            events.emit(run, AgentStepType.STATUS, "Agent failed", safeCounters(runtime));
            emitContextUsage(run, projectContext, runtime);
            return result(run, runtime, "Something went wrong while running the agent.", run.getError());
        } finally {
            changeSets.discard(run.getId());
            validation.clear(run.getId());
            stopQuietly(beat);
            queue.clearCancel(run.getId());
        }
    }

    private static final int CONVERSATIONAL_HISTORY_TURNS = 6;

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

        requireBeforeDeadline(deadlineNanos);
        AgentProperties.ModelSelection selection = props.modelFor(ProjectType.CHAT);
        log.info("model_called runId={} conversational=true provider={} model={}", run.getId(), selection.provider(),
                selection.model());
        LlmResult res = llm.chatStreaming(new LlmRequest(selection.provider(), selection.model(), convo, List.of(),
                props.getLlm().getTemperature()), stream::append);
        stream.flush();
        accountTokens(run, res);
        if (res.reasoning() != null && !res.reasoning().isBlank()) {
            events.emit(run, AgentStepType.THINKING, "Model reasoning", Map.of("reasoning", res.reasoning()));
        }
        stream.complete();
        run.setConversational(true);
        setStatus(run, RunStatus.DONE);
        log.info("agent_completed runId={} conversational=true", run.getId());
        events.emit(run, AgentStepType.STATUS, "Agent completed",
                Map.of("iterations", 1, "modelCalls", 1, "toolCalls", 0, "phase", "CONVERSATION"));
        return new AgentRunResult(run.getId(), run.getStatus(), stream.content(), null, 1, 1, 0);
    }

    private void requireNotCancelled(AgentRun run) {
        if (queue.isCancelRequested(run.getId()))
            throw new AgentCancelledException();
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

    private List<String> executeTools(List<AgentTool> available, List<ToolCall> calls, AgentContext ctx,
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
            events.emit(run, AgentStepType.TOOL_RESULT, item.call.name(), auditToolResult(result));
        }
        return results.stream().map(this::toModelToolResult).toList();
    }

    private static void recordPhase(AgentRuntimeContext runtime, String toolName) {
        if (toolName.equals("setPlan"))
            runtime.transitionPhase(AgentPhase.PLAN);
        else if (toolName.equals("updatePlanStep") && runtime.phase().ordinal() < AgentPhase.PLAN.ordinal())
            runtime.transitionPhase(AgentPhase.PLAN);
        else if (Set.of("createFile", "createFiles", "patchFile", "deleteFile", "renameFile").contains(toolName))
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

    private static boolean hasPreviewEntrypoint(AgentContext context) {
        if (!context.sandboxed())
            return true;
        return java.util.stream.Stream.of(
                "index.html", "client/index.html", "client/public/index.html", "frontend/index.html",
                "dist/index.html", "build/index.html", "client/dist/index.html", "client/build/index.html",
                "frontend/dist/index.html", "frontend/build/index.html")
                .anyMatch(path -> context.sandbox().regularFile(context.projectId(), context.sandboxRoot(), path));
    }

    private record PreparedToolCall(ToolCall call, Map<String, Object> args, AgentTool tool) {
    }

    private static Map<String, Object> auditToolArgs(Map<String, Object> args) {
        return Map.of("argumentNames", args.keySet().stream().sorted().toList());
    }

    private static Map<String, Object> auditToolResult(ToolExecutionResult result) {
        if (result.success() && result.output() instanceof ValidationReport report) {
            Map<String, Object> audit = new java.util.LinkedHashMap<>();
            audit.put("success", true);
            audit.put("validationPassed", report.changeValidationPassed());
            audit.put("operation", report.operation().name());
            audit.put("introducedDiagnosticCount", report.introducedDiagnostics().size());
            audit.put("preExistingDiagnosticCount", report.preExistingDiagnostics().size());
            audit.put("repairAttempts", report.repairAttempts());
            audit.put("repairAttemptsRemaining", report.repairAttemptsRemaining());
            audit.put("introducedDiagnostics", report.introducedDiagnostics());
            audit.put("preExistingDiagnostics", report.preExistingDiagnostics());
            return Map.copyOf(audit);
        }
        return result.success()
                ? Map.of("success", true)
                : Map.of("success", false, "errorCode", result.error().code());
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
        return objectMapper.writeValueAsString(ToolExecutionResult.failure(
                "RESULT_TOO_LARGE", "Tool result exceeded the model context limit"));
    }

    private String budgetToolResult(AgentRuntimeContext runtime, String result) {
        if (runtime.reserveContext(result.length()))
            return result;
        String exhausted = objectMapper.writeValueAsString(ToolExecutionResult.failure(
                "CONTEXT_BUDGET_EXCEEDED",
                "The result was omitted because the run context budget is exhausted; narrow the search or request a file summary"));
        runtime.reserveContext(exhausted.length());
        return exhausted;
    }

    private static String budgetAssistantContext(AgentRuntimeContext runtime, String content) {
        if (content == null || content.isBlank())
            return content;
        return runtime.reserveContext(content.length()) ? content : null;
    }

    private void requireModelCallAvailable(AgentRuntimeContext runtime) {
        if (runtime.modelCallCount() >= Math.max(1, props.getMaxModelCalls())) {
            throw new AgentLimitExceededException("Agent model-call limit reached");
        }
    }

    private void requireToolCallsAvailable(AgentRuntimeContext runtime, int requested) {
        if (requested < 0 || runtime.toolCallCount() + requested > Math.max(1, props.getMaxToolCalls())) {
            throw new AgentLimitExceededException("Agent tool-call limit reached");
        }
    }

    private static String currentUserRequest(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            Message message = messages.get(index);
            if (message.getRole() == com.code.pal.message.domain.MessageRole.USER)
                return message.getContent();
        }
        return "";
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
                "remainingContextChars", runtime.remainingContextChars());
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

    private List<Message> loadHistory(AgentRun run) {
        // desc(createdAt), desc(seq): seq only breaks a tie between a user turn and its
        // assistant
        // placeholder created in the same instant (see MessageSequence) — createdAt
        // still orders everything else.
        var page = messageRepository.findByProjectId(run.getProjectId(),
                PageRequest.of(0, HISTORY_CANDIDATE_LIMIT,
                        Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("seq"))));
        List<Message> ordered = new ArrayList<>(page.getContent());
        ordered.removeIf(message -> message.getId().equals(run.getMessageId())
                || message.getContent() == null || message.getContent().isBlank());
        ordered.sort(java.util.Comparator
                .comparing(Message::getCreatedAt, java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                .thenComparingLong(Message::getSeq));
        return List.copyOf(ordered);
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
        runRepository.save(run);
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
            projects.advanceLifecycle(run.getUserId(), run.getProjectId(), stage);
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
        runRepository.save(run);
    }

    private void updateAssistant(AgentRun run, String content, MessageStatus status) {
        messageRepository.findById(run.getMessageId()).ifPresent(message -> {
            message.setContent(content);
            message.setStatus(status);
            Message saved = messageRepository.save(message);
            realtime.sendToProject(run.getProjectId(),
                    RealtimeMessage.of("MESSAGE_UPSERT", MessageResponse.from(saved)));
        });
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
                runRepository.save(run);
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
