package com.code.pal.agent.plan;

import com.code.pal.agent.tool.AgentToolException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Mutable, run-local plan. Revisions are explicit so the model can correct assumptions after exploration. */
public final class AgentPlanState {
    private int version;
    private String revisionReason;
    private Instant updatedAt;
    private List<PlanStep> steps = List.of();

    public synchronized Snapshot revise(List<String> descriptions, String reason) {
        if (descriptions == null || descriptions.isEmpty()) {
            throw new AgentToolException("EMPTY_PLAN", "A plan must contain at least one concrete step");
        }
        List<PlanStep> next = new ArrayList<>();
        for (int i = 0; i < descriptions.size(); i++) {
            String value = descriptions.get(i) == null ? "" : descriptions.get(i).trim();
            if (value.isEmpty()) throw new AgentToolException("INVALID_PLAN_STEP", "Plan steps must not be blank");
            next.add(new PlanStep(i + 1, value, StepStatus.PENDING, null));
        }
        version++;
        revisionReason = reason == null ? "" : reason.trim();
        updatedAt = Instant.now();
        steps = List.copyOf(next);
        return snapshot();
    }

    public synchronized Snapshot updateStep(int id, StepStatus status, String note) {
        if (id < 1 || id > steps.size()) throw new AgentToolException("UNKNOWN_PLAN_STEP", "Plan step does not exist");
        List<PlanStep> next = new ArrayList<>(steps);
        PlanStep current = next.get(id - 1);
        next.set(id - 1, new PlanStep(id, current.description(), status, note));
        steps = List.copyOf(next);
        updatedAt = Instant.now();
        return snapshot();
    }

    public synchronized boolean exists() { return version > 0 && !steps.isEmpty(); }
    public synchronized Snapshot snapshot() { return new Snapshot(version, revisionReason, updatedAt, steps); }

    public enum StepStatus { PENDING, IN_PROGRESS, COMPLETED, SKIPPED }
    public record PlanStep(int id, String description, StepStatus status, String note) {}
    public record Snapshot(int version, String revisionReason, Instant updatedAt, List<PlanStep> steps) {}
}
