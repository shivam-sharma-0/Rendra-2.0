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
        if (!allowedToolNames.contains(toolName)) {
            return ToolExecutionResult.failure("TOOL_NOT_AVAILABLE", "Tool is not available for this agent run");
        }
        AgentTool tool = registry.get(toolName);
        if (tool == null) {
            return ToolExecutionResult.failure("UNKNOWN_TOOL", "Requested tool is not registered");
        }
        try {
            if (tool.mutating()) projects.requireManageable(context.userId(), context.projectId());
            else projects.requireAccessible(context.userId(), context.projectId());
        } catch (ApiException denied) {
            return ToolExecutionResult.failure("ACCESS_DENIED", "Project is not available to this user");
        }

        List<ToolValidationError> validationErrors = tool.validate(args);
        if (!validationErrors.isEmpty()) return ToolExecutionResult.validationFailure(validationErrors);

        try {
            return ToolExecutionResult.success(tool.execute(args, context));
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
        try {
            for (Callable<ToolExecutionResult> task : work) futures.add(pool.submit(task));
            List<ToolExecutionResult> results = new ArrayList<>(work.size());
            for (Future<ToolExecutionResult> future : futures) {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("Agent run timed out");
                results.add(future.get(remaining, TimeUnit.NANOSECONDS));
            }
            return results;
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
