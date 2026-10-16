package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.exception.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.lease.TrustedOperationPendingException;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.runtime.RunCancellation;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** Bounded executor for independent readonly tool calls from a single model response. */
@Slf4j
@Component
public class AgentToolExecutor {
    private final AgentProperties props;
    private final ToolRegistry registry;
    private final ProjectService projects;
    private ExecutorService pool;

    public AgentToolExecutor(AgentProperties props, ToolRegistry registry, ProjectService projects) {
        this.props = props;
        this.registry = registry;
        this.projects = projects;
    }

    @PostConstruct
    void start() {
        pool = Executors.newFixedThreadPool(Math.max(1, props.getToolConcurrency()), runnable -> {
            Thread thread = new Thread(runnable, "agent-tool");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stop() { if (pool != null) pool.shutdownNow(); }

    /**
     * Execute only a tool explicitly granted to this run. Authorization and validation happen
     * immediately before invocation, so a queued run cannot retain stale project access.
     */
    public ToolExecutionResult execute(String toolName, Collection<String> allowedToolNames,
                                       Map<String, Object> args, AgentContext context) {
        if(RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
            throw new LlmCancelledException();
        if (!allowedToolNames.contains(toolName)) {
            return ToolExecutionResult.failure("TOOL_NOT_AVAILABLE", "Tool is not available for this agent run");
        }
        AgentTool tool = registry.get(toolName);
        if (tool == null) {
            return ToolExecutionResult.failure("UNKNOWN_TOOL", "Requested tool is not registered");
        }
        try {
            if (tool.mutating()) projects.requireCanEdit(context.userId(), context.projectId());
            else projects.requireAccessible(context.userId(), context.projectId());
        } catch (ApiException denied) {
            return ToolExecutionResult.failure("ACCESS_DENIED", "Project is not available to this user");
        }

        List<ToolValidationError> validationErrors = tool.validate(args);
        if (!validationErrors.isEmpty()) return ToolExecutionResult.validationFailure(validationErrors);

        try {
            return ToolExecutionResult.success(tool.execute(args, context));
        } catch (TrustedOperationPendingException pending) {
            throw pending;
        } catch (LeaseLostException lost) {
            throw lost;
        } catch (LlmCancelledException cancelled) {
            throw cancelled;
        } catch (AgentToolException e) {
            return ToolExecutionResult.failure(e.code(), safeMessage(e), e.details());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure("INVALID_INPUT", safeMessage(e));
        } catch (ApiException e) {
            // Workflow/permission decisions carry user-safe messages the model can act on.
            return ToolExecutionResult.failure("WORKFLOW_BLOCKED", safeMessage(e));
        } catch (Exception e) {
            log.warn("tool_execution_failed tool={} errorType={} message={}", tool.name(), e.getClass().getSimpleName(),
                    e.getMessage() == null ? "" : e.getMessage().lines().findFirst().orElse(""));
            return ToolExecutionResult.failure("EXECUTION_FAILED", "Tool execution failed");
        }
    }

    /** Run independent read-only invocations concurrently while respecting the run deadline. */
    public List<ToolExecutionResult> executeParallel(List<Callable<ToolExecutionResult>> work, long deadlineNanos) {
        List<Future<ToolExecutionResult>> futures = new ArrayList<>(work.size());
        var cancellation=RunCancellation.current();
        var context=LlmCallContext.current();
        var fence=RunFence.current();
        try {
            for (Callable<ToolExecutionResult> task : work) futures.add(pool.submit(()-> {
                try(var generation=RunFence.bind(fence);
                    var stop=RunCancellation.bind(cancellation);
                    var owner=LlmCallContext.bind(context)) { return task.call(); }
            }));
            List<ToolExecutionResult> results = new ArrayList<>(work.size());
            for (Future<ToolExecutionResult> future : futures) {
                while(true) {
                    if(cancellation.isCancelled()) throw new LlmCancelledException();
                    long remaining=deadlineNanos-System.nanoTime();
                    if(remaining<=0) throw new IllegalStateException("Agent run timed out");
                    try { results.add(future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS));break; }
                    catch(TimeoutException waiting) { /* poll Stop without consuming the full deadline */ }
                }
            }
            return results;
        } catch (LlmCancelledException cancelled) {
            futures.forEach(future->future.cancel(true));throw cancelled;
        } catch (ExecutionException failed) {
            futures.forEach(future->future.cancel(true));
            if(failed.getCause() instanceof LeaseLostException lost) throw lost;
            if(failed.getCause() instanceof TrustedOperationPendingException pending) throw pending;
            if(failed.getCause() instanceof LlmCancelledException stopped) throw stopped;
            throw new IllegalStateException("Parallel tool execution failed",failed.getCause());
        } catch (InterruptedException interrupted) {
            futures.forEach(future->future.cancel(true));Thread.currentThread().interrupt();throw new LlmCancelledException();
        } catch (Exception e) {
            futures.forEach(future -> future.cancel(true));
            throw new IllegalStateException("Parallel tool execution failed: " + e.getMessage(), e);
        }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Invalid tool input";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
