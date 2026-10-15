package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmHttpException;
import com.code.pal.agent.llm.RetryableLlmException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ContractModelCallsTest {
    private final List<AgentProperties.ModelSelection> free = List.of(new AgentProperties.ModelSelection("mistral", "first"),
            new AgentProperties.ModelSelection("nvidia", "second"), new AgentProperties.ModelSelection("nvidia", "third"),
            new AgentProperties.ModelSelection("nvidia", "fourth"));

    private LlmClient.LlmRequest request() {
        return new LlmClient.LlmRequest("mistral", "first", List.of(LlmClient.LlmMessage.user("Synthetic")), List.of(), 0.1, Duration.ofSeconds(10));
    }

    @Test
    void failoverHasThreeDistinctFreeRoutesOneDeadlineAndBoundedRefunds() {
        var clock = new AtomicLong();
        var routes = new ArrayList<String>();
        var refunds = new ArrayList<String>();
        var calls = new ContractModelCalls(free, clock::get);
        assertThrows(RetryableLlmException.class, () -> calls.execute(request(), new ContractModelCalls.Attempts() {
            public LlmClient.LlmResult call(UUID logical, int ordinal, LlmClient.LlmRequest request) {
                routes.add(request.model());
                assertEquals(Duration.ofSeconds(10 - routes.size() + 1), request.budget());
                clock.addAndGet(1_000_000_000L);
                throw new RetryableLlmException("Unavailable", 0);
            }
            public boolean noCompletion(UUID logical, int ordinal, RuntimeException failure) { return true; }
            public void refund(UUID logical, int ordinal) { refunds.add(logical + ":" + ordinal); }
        }));
        assertEquals(List.of("first", "second", "third"), routes);
        assertEquals(3, refunds.size());
        assertEquals(3, refunds.stream().distinct().count());
    }

    @Test
    void unknownCompletionStopQuotaLeaseAndTerminalErrorsNeverFailOver() {
        for (RuntimeException failure : List.of(new LlmCancelledException(), new LlmHttpException(422, "Invalid"),
                new IllegalStateException("Quota or lease lost"), new RetryableLlmException("Unknown completion", 0))) {
            var count = new AtomicInteger();
            var refunds = new AtomicInteger();
            assertThrows(failure.getClass(), () -> new ContractModelCalls(free, () -> 0).execute(request(), new ContractModelCalls.Attempts() {
                public LlmClient.LlmResult call(UUID logical, int ordinal, LlmClient.LlmRequest request) {
                    count.incrementAndGet();
                    throw failure;
                }
                public boolean noCompletion(UUID logical, int ordinal, RuntimeException error) { return false; }
                public void refund(UUID logical, int ordinal) { refunds.incrementAndGet(); }
            }));
            assertEquals(1, count.get());
            assertEquals(0, refunds.get());
        }
    }

    @Test
    void successfulResponseEndsTheLogicalRequestAndNeverRefundsUsage() {
        var count = new AtomicInteger();
        var result = new ContractModelCalls(free, () -> 0).execute(request(), new ContractModelCalls.Attempts() {
            public LlmClient.LlmResult call(UUID logical, int ordinal, LlmClient.LlmRequest request) {
                count.incrementAndGet();
                return new LlmClient.LlmResult("Done", null, List.of(), new LlmClient.Usage(10, 5), "stop");
            }
            public boolean noCompletion(UUID logical, int ordinal, RuntimeException failure) { fail("No failure proof needed"); return false; }
            public void refund(UUID logical, int ordinal) { fail("No token or slot refund for completed calls"); }
        });
        assertEquals(1, count.get());
        assertEquals(15, result.usage().promptTokens() + result.usage().completionTokens());
    }
}
