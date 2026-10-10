package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmException;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import com.code.pal.agent.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every model call is recorded in the ledger; calls outside run totals also reserve their worst case. */
class MeteredLlmClientTest {

    private static final Long USER = 7L;
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID RUN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-03T05:30:00Z");

    private final AgentProperties properties = new AgentProperties();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final ModelCallRepository ledger = mock(ModelCallRepository.class);
    private final List<ModelCall> saved = new CopyOnWriteArrayList<>();
    private final Map<String, Long> reserved = new ConcurrentHashMap<>();
    private final AtomicInteger delegateCalls = new AtomicInteger();
    private final AgentUsagePolicy usage = new AgentUsagePolicy(properties, runs, mock(StringRedisTemplate.class),
            Clock.fixed(NOW, ZoneOffset.UTC), new TokenReservations() {
                @Override public synchronized long add(Long userId, String id, long tokens, Duration ttl) {
                    reserved.put(id, tokens);
                    return reserved.values().stream().mapToLong(Long::longValue).sum();
                }
                @Override public void remove(Long userId, String id) { reserved.remove(id); }
            }, ledger);

    MeteredLlmClientTest() {
        when(ledger.save(any(ModelCall.class))).thenAnswer(call -> {
            saved.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(runs.sumTokensByUserIdSince(eq(USER), any())).thenReturn(0L);
        when(ledger.sumUncountedTokensByUserIdSince(eq(USER), any())).thenReturn(0L);
        properties.getLimits().setDailyTokenQuota(100_000);
    }

    private MeteredLlmClient client(LlmClient.LlmResult result, RuntimeException failure) {
        return new MeteredLlmClient((request, onDelta) -> {
            delegateCalls.incrementAndGet();
            // While the call runs, its reservation is held (for calls outside a run's totals).
            if (failure != null) throw failure;
            return result;
        }, ledger, usage);
    }

    private static LlmClient.LlmRequest request(int maxTokens) {
        return new LlmClient.LlmRequest("mistral", "codestral", List.of(LlmClient.LlmMessage.user("x".repeat(400))), List.of(), 0.2)
                .withMaxTokens(maxTokens);
    }

    private static final LlmClient.LlmResult OK = new LlmClient.LlmResult("hi", null, List.of(), new LlmClient.Usage(120, 30), "stop");

    @Test
    void recordsARunCallWithoutReservingIt() {
        try (LlmCallContext.Scope ignored = LlmCallContext.bind(new LlmCallContext(USER, PROJECT, RUN, CallPurpose.AGENT, true))) {
            client(OK, null).chat(request(8_192));
        }
        ModelCall call = saved.getFirst();
        assertEquals("AGENT", call.getPurpose());
        assertEquals("OK", call.getOutcome());
        assertEquals(120, call.getPromptTokens());
        assertEquals(30, call.getCompletionTokens());
        assertEquals(RUN, call.getRunId());
        assertTrue(call.isCountedInRun());
        assertEquals(8_192, call.getMaxTokens());
        assertTrue(call.getEstimatedInputTokens() >= 100);
        assertTrue(reserved.isEmpty(), "the agent loop reserves its own calls");
    }

    @Test
    void aCallOutsideRunTotalsReservesAndReleases() {
        try (LlmCallContext.Scope ignored = LlmCallContext.bind(new LlmCallContext(USER, PROJECT, null, CallPurpose.REQUIREMENTS, false))) {
            client(OK, null).chat(request(2_000));
        }
        assertEquals("REQUIREMENTS", saved.getFirst().getPurpose());
        assertFalse(saved.getFirst().isCountedInRun());
        assertTrue(reserved.isEmpty(), "released after the call");
    }

    @Test
    void aCallTheQuotaCannotCoverIsNotMade() {
        when(runs.sumTokensByUserIdSince(eq(USER), any())).thenReturn(99_000L);
        try (LlmCallContext.Scope ignored = LlmCallContext.bind(new LlmCallContext(USER, PROJECT, RUN, CallPurpose.INTENT, false))) {
            assertThrows(LlmQuotaExceededException.class, () -> client(OK, null).chat(request(2_000)));
        }
        assertEquals(0, delegateCalls.get());
        assertEquals("REFUSED", saved.getFirst().getOutcome());
        assertTrue(reserved.isEmpty());
    }

    @Test
    void failedAndStoppedCallsAreRecordedAndRethrown() {
        try (LlmCallContext.Scope ignored = LlmCallContext.bind(new LlmCallContext(USER, PROJECT, RUN, CallPurpose.VISION, false))) {
            assertThrows(LlmException.class, () -> client(null, new LlmException("LLM call failed: HTTP 503")).chat(request(4_096)));
            assertThrows(LlmCancelledException.class, () -> client(null, new LlmCancelledException()).chat(request(4_096)));
        }
        assertEquals("FAILED", saved.get(0).getOutcome());
        assertEquals("LLM call failed: HTTP 503", saved.get(0).getError());
        assertEquals(0, saved.get(0).getPromptTokens());
        assertEquals("CANCELLED", saved.get(1).getOutcome());
        assertTrue(reserved.isEmpty(), "released on every path");
    }

    @Test
    void aBrokenLedgerNeverFailsTheCall() {
        when(ledger.save(any(ModelCall.class))).thenThrow(new IllegalStateException("database down"));
        LlmClient.LlmResult result = client(OK, null).chat(request(100));
        assertEquals("hi", result.content());
    }

    @Test
    void aCallOutsideAnyRequestIsRecordedAsOther() {
        client(OK, null).chat(request(100));
        assertEquals("OTHER", saved.getFirst().getPurpose());
        assertNull(saved.getFirst().getUserId());
        assertTrue(reserved.isEmpty());
    }

    @Test
    void dailyUsageCountsLedgerCallsOutsideRuns() {
        when(runs.sumTokensByUserIdSince(eq(USER), any())).thenReturn(5_000L);
        when(ledger.sumUncountedTokensByUserIdSince(eq(USER), any())).thenReturn(700L);
        assertEquals(5_700, usage.tokensUsedToday(USER));
        assertEquals(5_700, usage.dailyUsage(USER).tokensUsed());
    }
}
