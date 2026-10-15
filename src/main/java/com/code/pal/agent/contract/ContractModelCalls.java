package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.RetryableLlmException;
import com.code.pal.agent.llm.LlmException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Bounded logical call envelope. The runner adapter retains quota, admission, lease and physical ledger authority. */
public final class ContractModelCalls {
    public interface Attempts {
        LlmClient.LlmResult call(UUID logicalId, int ordinal, LlmClient.LlmRequest request);
        boolean noCompletion(UUID logicalId, int ordinal, RuntimeException failure);
        void refund(UUID logicalId, int ordinal);
    }

    private final List<AgentProperties.ModelSelection> freeRoutes;
    private final LongSupplier nanoTime;

    public ContractModelCalls(List<AgentProperties.ModelSelection> configuredFreeRoutes, LongSupplier nanoTime) {
        this.freeRoutes = configuredFreeRoutes.stream().distinct().toList();
        this.nanoTime = nanoTime;
    }

    public LlmClient.LlmResult execute(LlmClient.LlmRequest request, Attempts attempts) {
        var preferred = new AgentProperties.ModelSelection(request.provider(), request.model());
        if (!freeRoutes.contains(preferred) || request.budget() == null || request.budget().isZero() || request.budget().isNegative()) {
            throw new IllegalArgumentException("Contract call needs a configured FREE route and a shared deadline");
        }
        var routes = new ArrayList<AgentProperties.ModelSelection>();
        routes.add(preferred);
        freeRoutes.stream().filter(route -> !route.equals(preferred)).limit(2).forEach(routes::add);
        UUID logical = UUID.randomUUID();
        long started = nanoTime.getAsLong();
        long budget = request.budget().toNanos();
        for (int index = 0; index < routes.size(); index++) {
            long remaining = budget - (nanoTime.getAsLong() - started);
            if (remaining <= 0) {
                throw new LlmException("Structured model deadline exceeded");
            }
            var route = routes.get(index);
            var bounded = new LlmClient.LlmRequest(route.provider(), route.model(), request.messages(), request.tools(),
                    request.temperature(), Duration.ofNanos(remaining), request.toolChoice(), request.maxTokens(), request.jsonSchema());
            try {
                return attempts.call(logical, index + 1, bounded);
            } catch (RuntimeException failure) {
                boolean retryable = failure instanceof RetryableLlmException
                        || failure instanceof LlmHttpException http && http.modelUnavailable();
                if (!retryable || !attempts.noCompletion(logical, index + 1, failure)) {
                    throw failure;
                }
                // Idempotent durable slot refund only. Never delete/alter physical token/cost ledger rows.
                attempts.refund(logical, index + 1);
                if (index == routes.size() - 1) {
                    throw failure;
                }
            }
        }
        throw new IllegalStateException("No configured contract model route");
    }
}
