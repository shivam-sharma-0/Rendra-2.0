package com.code.pal.agent.usage;

import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmLogicalCall;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.agent.llm.OpenAiCompatibleLlmClient;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmAttemptScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.LlmStreamException;
import com.code.pal.agent.llm.RetryableLlmException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Primary gateway: every call owns durable capacity, including calls displayed in run totals. */
@Slf4j
@Primary
@Component
public class MeteredLlmClient implements LlmClient {

    private ProjectLeaseService leases;
    private final LlmClient delegate;
    private final AgentUsagePolicy usage;
    private final AgentProperties properties;
    private ProviderScheduler scheduler;
    @Autowired void useScheduler(ProviderScheduler scheduler){this.scheduler=scheduler;}

    @Autowired
    public MeteredLlmClient(OpenAiCompatibleLlmClient delegate, AgentUsagePolicy usage, AgentProperties properties,ProjectLeaseService leases) {
        this((LlmClient)delegate,usage,properties);this.leases=leases;
    }

    public MeteredLlmClient(OpenAiCompatibleLlmClient delegate, AgentUsagePolicy usage, AgentProperties properties) {
        this((LlmClient) delegate, usage, properties);
    }

    MeteredLlmClient(LlmClient delegate, AgentUsagePolicy usage, AgentProperties properties) {
        this.delegate = delegate;
        this.usage = usage;
        this.properties = properties;
    }

    @Override
    public LlmResult chatStreaming(LlmRequest original, Consumer<String> onTextDelta) {
        if (RunCancellation.current().isCancelled() || Thread.currentThread().isInterrupted()) throw new LlmCancelledException();
        LlmCallContext context = LlmCallContext.current();
        if (context.userId() == null) throw new LlmAccountingException("This model call has no accounting owner");
        int cap = original.maxTokens() == null ? properties.getLlm().getMaxOutputTokens() : original.maxTokens();
        String provider = original.provider() == null || original.provider().isBlank() ? "openrouter" : original.provider().trim().toLowerCase(Locale.ROOT);
        LlmRequest request = new LlmRequest(provider, original.model(), original.messages(), original.tools(),
                original.temperature(), original.budget(), original.toolChoice(), cap,original.jsonSchema());
        UUID logicalId = LlmLogicalCall.currentOrNew();
        if (delegate instanceof OpenAiCompatibleLlmClient) {
            try (var ignored = LlmAttemptScope.bind((attempt, transmission) ->
                    executeAttempt(context, attempt, logicalId, transmission))) {
                return delegate.chatStreaming(request, onTextDelta);
            }
        }
        return executeAttempt(context, request, logicalId, () -> delegate.chatStreaming(request, onTextDelta));
    }

    private LlmResult executeAttempt(LlmCallContext context, LlmRequest request, UUID logicalId,
                                     Supplier<LlmResult> transmission) {
        if (RunCancellation.current().isCancelled()) throw new LlmCancelledException();
        if(leases!=null && context.runId()!=null) leases.checkCurrent(context.projectId());
        long estimate = VerifiedTokenBounds.reservationInput(properties, request);
        long admissionStarted=System.nanoTime();
        try(var permit=scheduler==null?null:scheduler.acquire(context,request);
            var closed=LlmAttemptScope.onResponseClosed(()->{if(permit!=null)permit.completed();})) {
        if(request.budget()!=null && System.nanoTime()-admissionStarted>=request.budget().toNanos())throw new LlmException("Scheduling exhausted the attempt budget before reservation/transmission");
        DailyTokenBudget.Grant grant = usage.reserve(context, request, estimate, logicalId, UUID.randomUUID());
        if (!grant.granted()) throw new LlmQuotaExceededException(grant.refusal());
        try {
            if (RunCancellation.current().isCancelled()) {
                usage.releaseUnsent(grant.id());
                throw new LlmCancelledException();
            }
            usage.markSent(grant.id());
            if(permit!=null)permit.sent();
        } catch (LlmCancelledException cancelled) { throw cancelled; }
        catch (RuntimeException unavailable) {
            // A stale reservation or midnight refusal is safe to release only while durably unsent.
            try { usage.releaseUnsent(grant.id()); } catch (RuntimeException ambiguous) { /* keep held */ }
            if(unavailable instanceof LlmQuotaExceededException refused) throw refused;
            throw new LlmAccountingException(unavailable);
        }
        long started = System.nanoTime();
        LlmResult result;
        try { result = transmission.get();if(permit!=null)permit.completed(); }
        catch (LlmCancelledException cancelled) {
            finish(grant, started, "CANCELLED", null, null);
            throw cancelled;
        } catch (RuntimeException failed) {
            if(permit!=null && failed instanceof LlmHttpException)permit.completed();
            boolean rejected=failed instanceof LlmHttpException http && Set.of(400,401,403,404,405,410,413,415,422,429).contains(http.status())
                    || failed instanceof RetryableLlmException retry && retry.rateLimited();
            Usage report = failed instanceof LlmStreamException stream ? stream.usage() : rejected ? new Usage(0,0) : null;
            finish(grant, started, "FAILED", failureCode(failed), report);
            throw failed;
        }
        finish(grant, started, "OK", null, result.usage());
        if (usage.boundExceeded(grant.id()))
            throw new LlmAccountingException("Provider usage exceeded its verified limit; new calls are suspended");
        return result;
        }
    }

    private void finish(DailyTokenBudget.Grant grant, long started, String outcome, String error, Usage report) {
        try {
            usage.finishAttempt(grant.id(), report, outcome, error,
                    (int) Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
        } catch (RuntimeException unavailable) {
            // Retaining the full bound is safe even if the ledger write or settlement failed/was ambiguous.
            try { usage.retainUnknown(grant.id()); }
            catch (RuntimeException stillUnavailable) { log.warn("model_attempt_accounting_pending attemptId={}", grant.id()); }
            throw new LlmAccountingException(unavailable);
        }
    }

    private static String failureCode(RuntimeException failure) {
        if(failure instanceof RetryableLlmException retry && retry.rateLimited())return "HTTP 429";
        if (failure instanceof LlmHttpException http) return "HTTP " + http.status();
        if (failure instanceof LlmStreamException stream) return "STREAM " + stream.reason()
                + (stream.providerCode() == null ? "" : " " + stream.providerCode());
        return failure.getClass().getSimpleName(); // No upstream response bodies, prompts or credentials in the ledger.
    }
}
