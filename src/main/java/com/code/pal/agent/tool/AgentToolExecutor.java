package com.code.pal.agent.tool;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.exception.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

/** Bounded executor for independent readonly tool calls from a single model response. */
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
        if(com.code.pal.agent.runtime.RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted())
            throw new com.code.pal.agent.llm.LlmCancelledException();
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
        } catch (com.code.pal.agent.lease.TrustedOperationPendingException pending) {
            throw pending;
        } catch (com.code.pal.agent.lease.LeaseLostException lost) {
            throw lost;
        } catch (com.code.pal.agent.llm.LlmCancelledException cancelled) {
            throw cancelled;
        } catch (AgentToolException e) {
            return ToolExecutionResult.failure(e.code(), safeMessage(e), e.details());
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure("INVALID_INPUT", safeMessage(e));
        } catch (Exception e) {
            return ToolExecutionResult.failure("EXECUTION_FAILED", "Tool execution failed");
        }
    }

    /** Run independent read-only invocations concurrently while respecting the run deadline. */
    public List<ToolExecutionResult> executeParallel(List<Callable<ToolExecutionResult>> work, long deadlineNanos) {
        List<Future<ToolExecutionResult>> futures = new ArrayList<>(work.size());
        var cancellation=com.code.pal.agent.runtime.RunCancellation.current();
        var context=com.code.pal.agent.llm.LlmCallContext.current();
        var fence=com.code.pal.agent.lease.RunFence.current();
        try {
            for (Callable<ToolExecutionResult> task : work) futures.add(pool.submit(()-> {
                try(var generation=com.code.pal.agent.lease.RunFence.bind(fence);
                    var stop=com.code.pal.agent.runtime.RunCancellation.bind(cancellation);
                    var owner=com.code.pal.agent.llm.LlmCallContext.bind(context)) { return task.call(); }
            }));
            List<ToolExecutionResult> results = new ArrayList<>(work.size());
            for (Future<ToolExecutionResult> future : futures) {
                while(true) {
                    if(cancellation.isCancelled()) throw new com.code.pal.agent.llm.LlmCancelledException();
                    long remaining=deadlineNanos-System.nanoTime();
                    if(remaining<=0) throw new IllegalStateException("Agent run timed out");
                    try { results.add(future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS));break; }
                    catch(java.util.concurrent.TimeoutException waiting) { /* poll Stop without consuming the full deadline */ }
                }
            }
            return results;
        } catch (com.code.pal.agent.llm.LlmCancelledException cancelled) {
            futures.forEach(future->future.cancel(true));throw cancelled;
        } catch (java.util.concurrent.ExecutionException failed) {
            futures.forEach(future->future.cancel(true));
            if(failed.getCause() instanceof com.code.pal.agent.lease.LeaseLostException lost) throw lost;
            if(failed.getCause() instanceof com.code.pal.agent.lease.TrustedOperationPendingException pending) throw pending;
            if(failed.getCause() instanceof com.code.pal.agent.llm.LlmCancelledException stopped) throw stopped;
            throw new IllegalStateException("Parallel tool execution failed",failed.getCause());
        } catch (InterruptedException interrupted) {
            futures.forEach(future->future.cancel(true));Thread.currentThread().interrupt();throw new com.code.pal.agent.llm.LlmCancelledException();
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
