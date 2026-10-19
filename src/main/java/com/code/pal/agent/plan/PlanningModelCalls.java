package com.code.pal.agent.plan;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.config.AgentProperties.ModelSelection;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Plan-only failover. Each attempt retains its own accounting and run slot; nothing is refunded. */
@Slf4j
public final class PlanningModelCalls {
    private final AgentProperties properties;
    private final LongSupplier nanoTime;

    public PlanningModelCalls(AgentProperties properties) { this(properties, System::nanoTime); }

    PlanningModelCalls(AgentProperties properties, LongSupplier nanoTime) {
        this.properties = properties;
        this.nanoTime = nanoTime;
    }

    /** One existing schema-correction slot may use another explicitly configured planning route. */
    public ModelSelection schemaCorrectionRoute(ModelSelection primary) {
        var policy = properties.getPlanning();
        var routes = properties.planningRoutes();
        if (!policy.isCorrectionFallback() || policy.getMaxRouteAttempts() < 2 || !routes.contains(primary))
            return primary;
        return routes.stream().limit(Math.clamp(policy.getMaxRouteAttempts(), 1, 3))
                .filter(route -> !route.equals(primary)).filter(properties::available).findFirst().orElse(primary);
    }

    public LlmClient.LlmResult execute(LlmClient.LlmRequest request,
            Duration available, Function<LlmClient.LlmRequest, LlmClient.LlmResult> attempt, Runnable requireActive) {
        Duration budget = request.budget();
        if (budget == null || budget.compareTo(available) > 0) budget = available;
        var bounded = new LlmClient.LlmRequest(request.provider(), request.model(), request.messages(), request.tools(),
                request.temperature(), budget, request.toolChoice(), request.maxTokens(), request.jsonSchema());
        return execute(bounded, attempt, requireActive);
    }

    public LlmClient.LlmResult execute(LlmClient.LlmRequest request,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> attempt, Runnable requireActive) {
        if (request.budget() == null || request.budget().isZero() || request.budget().isNegative()) {
            throw new IllegalArgumentException("Planning requires a positive shared time budget");
        }
        var primary = new ModelSelection(request.provider(), request.model());
        var configured = LlmCallContext.current().purpose() == CallPurpose.REQUIREMENTS
                ? properties.requirementsRoutes() : properties.planningRoutes();
        var routes = new ArrayList<ModelSelection>();
        if (!configured.contains(primary) || properties.available(primary)) routes.add(primary);
        if (configured.contains(primary)) configured.stream().filter(route -> !route.equals(primary))
                .filter(properties::available).forEach(routes::add);
        if (routes.isEmpty()) throw new LlmException("No available configured planning route remains");
        int limit = Math.min(routes.size(), Math.clamp(properties.getPlanning().getMaxRouteAttempts(), 1, 3));
        long started = nanoTime.getAsLong();
        long budget = request.budget().toNanos();
        for (int index = 0; index < limit; index++) {
            if (Thread.currentThread().isInterrupted() || RunCancellation.current().isCancelled()) {
                throw new LlmCancelledException();
            }
            requireActive.run();
            long remaining = budget - (nanoTime.getAsLong() - started);
            if (remaining <= 0) throw new LlmException("Planning shared time budget exhausted");
            var route = routes.get(index);
            long routeNanos = Duration.ofMillis(Math.max(1, properties.getPlanning().getRouteTimeoutMs())).toNanos();
            Integer outputCap = request.maxTokens();
            var verified = properties.getAccountingBounds().get(route.provider() + ":" + route.model());
            if (outputCap != null && verified != null && verified.getOutputTokens() > 0)
                outputCap = Math.min(outputCap, verified.getOutputTokens());
            var bounded = new LlmClient.LlmRequest(route.provider(), route.model(), request.messages(), request.tools(),
                    request.temperature(), Duration.ofNanos(Math.min(remaining, routeNanos)), request.toolChoice(),
                    outputCap, properties.getPlanning().getSchemaProviders().contains(route.provider()) ? request.jsonSchema() : null);
            try {
                return attempt.apply(bounded);
            } catch (LlmException failure) {
                if (!recoverable(failure)) throw failure;
                properties.markModelUnavailable(route);
                boolean fallback = index + 1 < limit;
                log.warn("planning_route_failed provider={} model={} failure={} providerCode={} fallbackAvailable={}",
                        route.provider(), route.model(), failure.getClass().getSimpleName(),
                        failure instanceof LlmStreamException stream ? stream.providerCode() : null, fallback);
                if (!fallback) throw failure;
            }
        }
        throw new IllegalStateException("Planning route list was empty");
    }

    private static boolean recoverable(LlmException failure) {
        if (failure instanceof LlmCancelledException || failure instanceof LlmQuotaExceededException
                || failure instanceof LlmAccountingException) return false;
        if (failure instanceof LlmStreamException stream) {
            Integer code = stream.providerCode();
            return stream.reason() == LlmStreamException.Reason.INTERRUPTED_STREAM
                    || stream.reason() == LlmStreamException.Reason.PROVIDER_ERROR && code != null
                    && (code >= 500 || code == 429 || code == 403 || code == 404 || code == 410);
        }
        if (failure instanceof LlmHttpException http) {
            return http.status() >= 500 || http.status() == 429 || http.status() == 401 || http.modelUnavailable();
        }
        return failure instanceof RetryableLlmException || failure.getCause() instanceof RetryableLlmException;
    }
}
