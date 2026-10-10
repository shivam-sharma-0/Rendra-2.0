package com.code.pal.agent.runtime;

import com.code.pal.agent.llm.LlmClient.LlmMessage;
import com.code.pal.agent.plan.AgentPlanState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mutable state owned by one invocation of the agent loop; never shared between runs. */
public final class AgentRuntimeContext {
    private final UUID runId;
    private final UUID projectId;
    private final Long userId;
    private final String userRequest;
    private final List<LlmMessage> messages;
    private int iteration;
    private int toolCallCount;
    private int modelCallCount;
    private final int maxContextChars;
    private int contextChars;
    private int compactedChars;
    private AgentRuntimeStatus status = AgentRuntimeStatus.CREATED;
    private AgentPhase phase = AgentPhase.UNDERSTAND;
    private final AgentPlanState plan;

    public AgentRuntimeContext(UUID runId, UUID projectId, Long userId, String userRequest,
                               List<LlmMessage> messages) {
        this(runId, projectId, userId, userRequest, messages, Integer.MAX_VALUE,
                messages.stream().mapToInt(message -> message.content() == null ? 0 : message.content().length()).sum());
    }

    public AgentRuntimeContext(UUID runId, UUID projectId, Long userId, String userRequest,
                               List<LlmMessage> messages, int maxContextChars, int initialContextChars) {
        this(runId, projectId, userId, userRequest, messages, maxContextChars, initialContextChars, new AgentPlanState());
    }

    public AgentRuntimeContext(UUID runId, UUID projectId, Long userId, String userRequest,
                               List<LlmMessage> messages, int maxContextChars, int initialContextChars,
                               AgentPlanState plan) {
        this.runId = runId;
        this.projectId = projectId;
        this.userId = userId;
        this.userRequest = userRequest;
        this.messages = new ArrayList<>(messages);
        this.maxContextChars = Math.max(1, maxContextChars);
        this.contextChars = Math.max(0, initialContextChars);
        this.plan = plan;
    }

    public UUID runId() { return runId; }
    public UUID projectId() { return projectId; }
    public Long userId() { return userId; }
    public String userRequest() { return userRequest; }
    public List<LlmMessage> messages() { return messages; }
    public int iteration() { return iteration; }
    public int toolCallCount() { return toolCallCount; }
    public int modelCallCount() { return modelCallCount; }
    public int contextChars() { return contextChars; }
    public int remainingContextChars() { return Math.max(0, maxContextChars - contextChars); }
    public AgentRuntimeStatus status() { return status; }
    public AgentPhase phase() { return phase; }
    public AgentPlanState plan() { return plan; }

    public void beginIteration() { iteration++; }
    public void recordModelCall() { modelCallCount++; }
    public void recordToolCalls(int count) { toolCallCount += count; }
    public void transitionTo(AgentRuntimeStatus next) { status = next; }
    public void transitionPhase(AgentPhase next) { phase = next; }

    /**
     * Records a compaction: {@code budgetChars} (tool results, which count against the context budget) are
     * given back; {@code otherChars} (call arguments, never budgeted) are only counted.
     */
    public void recordCompaction(int budgetChars, int otherChars) {
        contextChars = Math.max(0, contextChars - Math.max(0, budgetChars));
        compactedChars += Math.max(0, budgetChars) + Math.max(0, otherChars);
    }

    /** Characters removed by compaction during the run. */
    public int compactedChars() { return compactedChars; }

    public boolean reserveContext(int chars) {
        int requested = Math.max(0, chars);
        if (requested > remainingContextChars()) return false;
        contextChars += requested;
        return true;
    }
}
