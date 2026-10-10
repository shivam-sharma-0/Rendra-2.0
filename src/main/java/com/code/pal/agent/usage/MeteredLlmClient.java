package com.code.pal.agent.usage;

import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.agent.llm.OpenAiCompatibleLlmClient;
import com.code.pal.agent.llm.TokenEstimate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The model gateway the application uses: every call goes through the provider client and is recorded
 * in the call ledger ({@link ModelCall}) with who it was for ({@link LlmCallContext}), its tokens,
 * duration and outcome. Calls whose tokens no run total includes (intent, vision, requirements) also
 * reserve their worst case in the user's daily quota here; the agent loop reserves its own calls.
 */
@Slf4j
@Primary
@Component
public class MeteredLlmClient implements LlmClient {

    private static final int MAX_ERROR = 300;

    private final LlmClient delegate;
    private final ModelCallRepository calls;
    private final AgentUsagePolicy usage;

    @Autowired
    public MeteredLlmClient(OpenAiCompatibleLlmClient delegate, ModelCallRepository calls, AgentUsagePolicy usage) {
        this((LlmClient) delegate, calls, usage);
    }

    MeteredLlmClient(LlmClient delegate, ModelCallRepository calls, AgentUsagePolicy usage) {
        this.delegate = delegate;
        this.calls = calls;
        this.usage = usage;
    }

    @Override
    public LlmResult chatStreaming(LlmRequest request, Consumer<String> onTextDelta) {
        LlmCallContext context = LlmCallContext.current();
        long estimate = TokenEstimate.input(request);
        int cap = request.maxTokens() == null ? 0 : request.maxTokens();
        AgentUsagePolicy.Reservation reservation = null;
        if (!context.countedInRun() && context.userId() != null) {
            reservation = usage.reserve(context.userId(), estimate + cap);
            if (!reservation.granted()) {
                record(context, request, estimate, 0, "REFUSED", reservation.refusal(), null);
                throw new LlmQuotaExceededException(reservation.refusal());
            }
        }
        long started = System.nanoTime();
        try {
            LlmResult result = delegate.chatStreaming(request, onTextDelta);
            record(context, request, estimate, started, "OK", null, result.usage());
            return result;
        } catch (LlmCancelledException cancelled) {
            record(context, request, estimate, started, "CANCELLED", null, null);
            throw cancelled;
        } catch (LlmException failed) {
            record(context, request, estimate, started, "FAILED", failed.getMessage(), null);
            throw failed;
        } finally {
            // After the ledger row is saved, so the call's tokens are never counted nowhere in between.
            usage.release(reservation);
        }
    }

    /** Never fails the call: a ledger that cannot be written is logged and skipped. */
    private void record(LlmCallContext context, LlmRequest request, long estimate, long startedNanos, String outcome,
                        String error, Usage used) {
        try {
            calls.save(ModelCall.builder()
                    .userId(context.userId()).projectId(context.projectId()).runId(context.runId())
                    .purpose(context.purpose().name()).provider(request.provider()).model(request.model())
                    .promptTokens(used == null ? 0 : used.promptTokens())
                    .completionTokens(used == null ? 0 : used.completionTokens())
                    .estimatedInputTokens((int) Math.min(Integer.MAX_VALUE, estimate))
                    .maxTokens(request.maxTokens())
                    .durationMs(startedNanos == 0 ? 0 : (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos))
                    .outcome(outcome).error(clip(error)).countedInRun(context.countedInRun())
                    .build());
        } catch (RuntimeException e) {
            log.warn("model_call_ledger_failed purpose={} outcome={} reason={}", context.purpose(), outcome, e.getMessage());
        }
    }

    private static String clip(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.strip();
        return trimmed.length() <= MAX_ERROR ? trimmed : trimmed.substring(0, MAX_ERROR);
    }
}
