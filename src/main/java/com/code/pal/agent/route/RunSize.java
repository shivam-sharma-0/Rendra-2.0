package com.code.pal.agent.route;

import com.code.pal.agent.config.AgentProperties;

import java.util.List;

/** A run's size tier, why it was chosen, and the time limit and budgets that follow from it. */
public record RunSize(SizeTier tier, List<String> reasons, long timeoutMs, int maxIterations, int maxModelCalls, int maxToolCalls) {

    public RunSize {
        reasons = List.copyOf(reasons);
    }

    /** The same size with a time limit of at least {@code timeoutMs}, and why. */
    public RunSize withLongerTimeout(long timeoutMs, String reason) {
        if (timeoutMs <= this.timeoutMs) return this;
        List<String> withReason = new java.util.ArrayList<>(reasons);
        withReason.add(reason);
        return new RunSize(tier, withReason, timeoutMs, maxIterations, maxModelCalls, maxToolCalls);
    }

    /** The limits configured for {@code tier}: today's for S, the larger ones for M and L. */
    public static RunSize of(SizeTier tier, List<String> reasons, AgentProperties props) {
        if (tier == SizeTier.S) {
            return new RunSize(tier, reasons, props.getRunTimeoutMs(), props.getMaxIterations(), props.getMaxModelCalls(),
                    props.getMaxToolCalls());
        }
        return new RunSize(tier, reasons, Math.max(props.getRunTimeoutMs(), props.getLargeRunTimeoutMs()),
                Math.max(props.getMaxIterations(), props.getLargeRunMaxIterations()),
                Math.max(props.getMaxModelCalls(), props.getLargeRunMaxModelCalls()),
                Math.max(props.getMaxToolCalls(), props.getLargeRunMaxToolCalls()));
    }
}
