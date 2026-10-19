package com.code.pal.agent.plan;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.runtime.RunCancellation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PlanningModelCallsTest {
    private final AgentProperties properties = new AgentProperties();
    private final LlmClient.LlmRequest request = new LlmClient.LlmRequest("openrouter", "primary",
            List.of(LlmClient.LlmMessage.user("Approved task scope")), List.of(), 0, Duration.ofSeconds(90)).withMaxTokens(2000);
    private final LlmClient.LlmResult result = new LlmClient.LlmResult("plan", null, List.of(), new LlmClient.Usage(12, 3), "stop");

    PlanningModelCallsTest() {
        properties.getModels().setPlan(List.of("openrouter:primary", "nvidia:secondary", "openrouter:third"));
        properties.getModels().setFreePlan(List.of("openrouter:free-primary", "nvidia:free-secondary"));
        properties.getModels().setBuild(List.of("openrouter:coding-only"));
    }

    @Test void sse503UsesOneExplicitPlanningFallbackWithoutReturningPartialOutput() {
        var calls = new ArrayList<LlmClient.LlmRequest>();
        var failure = new LlmStreamException(LlmStreamException.Reason.PROVIDER_ERROR, 503, null);
        var actual = new PlanningModelCalls(properties).execute(request, routed -> {
            calls.add(routed);
            if (calls.size() == 1) throw failure;
            return result;
        }, () -> {});
        assertSame(result, actual);
        assertEquals(List.of("primary", "secondary"), calls.stream().map(LlmClient.LlmRequest::model).toList());
        assertEquals(request.messages(), calls.getLast().messages());
        assertEquals(2000, calls.getLast().maxTokens());
        assertFalse(properties.available(new AgentProperties.ModelSelection("openrouter", "primary")));
    }

    @Test void callerBudgetClampsALargeContractDeadlineAndCannotRestartOnFallback() {
        var time = new AtomicLong();
        var calls = new ArrayList<LlmClient.LlmRequest>();
        var large = new LlmClient.LlmRequest(request.provider(), request.model(), request.messages(), request.tools(),
                0, Duration.ofMinutes(15)).withMaxTokens(2000);
        new PlanningModelCalls(properties, time::get).execute(large, Duration.ofSeconds(90), routed -> {
            calls.add(routed);
            if (calls.size() == 1) {
                time.set(Duration.ofSeconds(70).toNanos());
                throw new LlmHttpException(503, "unavailable");
            }
            return result;
        }, () -> {});
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofSeconds(20)),
                calls.stream().map(LlmClient.LlmRequest::budget).toList());
        assertEquals(large.messages(), calls.getLast().messages());
        assertEquals(2000, calls.getLast().maxTokens());
        properties.markModelAvailable(new AgentProperties.ModelSelection(request.provider(), request.model()));
        calls.clear();
        var smaller = new LlmClient.LlmRequest(request.provider(), request.model(), request.messages(), List.of(),
                0, Duration.ofSeconds(30));
        new PlanningModelCalls(properties, time::get).execute(smaller, Duration.ofSeconds(90), routed -> {
            assertEquals(Duration.ofSeconds(30), routed.budget());
            return result;
        }, () -> {});
    }

    @Test void partialPrimaryTimeoutGivesCodestralItsOwnSliceWithinTheRunDeadline() {
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getModels().setPlan(List.of("openrouter:primary", "mistral:codestral"));
        var time = new AtomicLong();
        var calls = new ArrayList<LlmClient.LlmRequest>();
        var contract = new LlmClient.LlmRequest(request.provider(), request.model(), request.messages(), List.of(),
                0, Duration.ofMinutes(15)).withMaxTokens(2000);
        assertSame(result, new PlanningModelCalls(properties, time::get).execute(contract,
                Duration.ofSeconds(250), routed -> {
                    calls.add(routed);
                    if (calls.size() == 1) {
                        time.addAndGet(routed.budget().toNanos());
                        throw new LlmStreamException(LlmStreamException.Reason.INTERRUPTED_STREAM, null, null);
                    }
                    return result;
                }, () -> {}));
        assertEquals(List.of("primary", "codestral"), calls.stream().map(LlmClient.LlmRequest::model).toList());
        assertEquals(List.of(Duration.ofSeconds(90), Duration.ofSeconds(90)),
                calls.stream().map(LlmClient.LlmRequest::budget).toList());
        assertTrue(time.get() + calls.getLast().budget().toNanos() <= Duration.ofSeconds(250).toNanos());
    }

    @Test void schemaIsNeverForwardedToAnUnsupportedFallbackProvider() {
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.getModels().setPlan(List.of("mistral:primary", "openrouter:secondary"));
        properties.getPlanning().setSchemaProviders(java.util.Set.of("mistral"));
        var schema = new LlmClient.JsonSchema("proposal", "{\"type\":\"object\"}");
        var scoped = new LlmClient.LlmRequest("mistral", "primary", request.messages(), List.of(), 0, request.budget())
                .withJsonSchema(schema);
        var calls = new ArrayList<LlmClient.LlmRequest>();
        new PlanningModelCalls(properties).execute(scoped, routed -> {
            calls.add(routed);
            if (calls.size() == 1) {
                assertSame(schema, routed.jsonSchema());
                throw new LlmHttpException(503, "unavailable");
            }
            assertEquals("openrouter", routed.provider());
            assertNull(routed.jsonSchema());
            return result;
        }, () -> {});
        assertEquals(2, calls.size());
    }

    @Test void schemaCorrectionUsesOnlyEligibleConfiguredRoutesAndHonorsOperatorControls() {
        var calls = new PlanningModelCalls(properties);
        var primary = new AgentProperties.ModelSelection("openrouter", "primary");
        var secondary = new AgentProperties.ModelSelection("nvidia", "secondary");
        assertEquals(secondary, calls.schemaCorrectionRoute(primary));
        properties.markModelUnavailable(secondary);
        assertEquals(primary, calls.schemaCorrectionRoute(primary)); // Third route is outside the configured bound.
        properties.getPlanning().setCorrectionFallback(false);
        assertEquals(primary, calls.schemaCorrectionRoute(primary));
        properties.getPlanning().setCorrectionFallback(true);
        properties.getPlanning().setMaxRouteAttempts(1);
        assertEquals(primary, calls.schemaCorrectionRoute(primary));
        properties.getPlanning().setMaxRouteAttempts(2);
        properties.setBillingMode(AgentProperties.BillingMode.FREE);
        assertEquals(primary, calls.schemaCorrectionRoute(primary)); // Paid pool is not available in FREE mode.
        var freePrimary = new AgentProperties.ModelSelection("openrouter", "free-primary");
        assertEquals(new AgentProperties.ModelSelection("nvidia", "free-secondary"), calls.schemaCorrectionRoute(freePrimary));
    }

    @Test void failedRoutesAreNotRepeatedAndAnUnrelatedCodingPoolIsNeverUsed() {
        var calls = new ArrayList<String>();
        var failure = new LlmStreamException(LlmStreamException.Reason.PROVIDER_ERROR, 503, null);
        assertSame(failure, assertThrows(LlmException.class, () -> new PlanningModelCalls(properties).execute(request, routed -> {
            calls.add(routed.model());
            throw failure;
        }, () -> {})));
        assertEquals(List.of("primary", "secondary"), calls);
    }

    @Test void freeModeUsesOnlyItsOwnConfiguredPlanningRoutes() {
        properties.setBillingMode(AgentProperties.BillingMode.FREE);
        var calls = new ArrayList<String>();
        var free = new LlmClient.LlmRequest("openrouter", "free-primary", request.messages(), List.of(), 0, request.budget());
        new PlanningModelCalls(properties).execute(free, routed -> {
            calls.add(routed.model());
            if (calls.size() == 1) throw new LlmHttpException(503, "unavailable");
            return result;
        }, () -> {});
        assertEquals(List.of("free-primary", "free-secondary"), calls);
    }

    @Test void schemaCorrectionDoesNotRecallAPrimaryThatAlreadyFailed() {
        properties.markModelUnavailable(new AgentProperties.ModelSelection("openrouter", "primary"));
        var calls = new ArrayList<String>();
        new PlanningModelCalls(properties).execute(request, routed -> {
            calls.add(routed.model()); return result;
        }, () -> {});
        assertEquals(List.of("secondary"), calls);
    }

    @Test void fallbackCannotExceedItsOwnVerifiedOutputCapacity() {
        var bound = new AgentProperties.AccountingBound();
        bound.setOutputTokens(1000);
        properties.getAccountingBounds().put("nvidia:secondary", bound);
        var caps = new ArrayList<Integer>();
        new PlanningModelCalls(properties).execute(request, routed -> {
            caps.add(routed.maxTokens());
            if (caps.size() == 1) throw new LlmHttpException(503, "unavailable");
            return result;
        }, () -> {});
        assertEquals(List.of(2000, 1000), caps);
    }

    @Test void quotaAccountingCancellationAndInvalidPayloadNeverFailOver() {
        for (var failure : List.of(new LlmCancelledException(), new LlmAccountingException("fixture"),
                new LlmQuotaExceededException("fixture"), new LlmHttpException(400, "invalid"),
                new LlmException("failed after partial output"))) {
            var count = new AtomicInteger();
            assertSame(failure, assertThrows(LlmException.class, () -> new PlanningModelCalls(properties).execute(request, routed -> {
                count.incrementAndGet(); throw failure;
            }, () -> {})));
            assertEquals(1, count.get());
        }
    }

    @Test void cancellationBeforeFallbackDoesNotSendAnotherAttempt() {
        var cancellation = new RunCancellation(UUID.randomUUID());
        var count = new AtomicInteger();
        try (var ignored = RunCancellation.bind(cancellation)) {
            assertThrows(LlmCancelledException.class, () -> new PlanningModelCalls(properties).execute(request, routed -> {
                count.incrementAndGet(); cancellation.cancel(); throw new LlmHttpException(503, "unavailable");
            }, () -> {}));
        }
        assertEquals(1, count.get());
    }

    @Test void routeTimeoutAndFallbackShareTheOriginalTimeBudget() {
        properties.getPlanning().setRouteTimeoutMs(45_000);
        var clock = new AtomicLong();
        var budgets = new ArrayList<Duration>();
        new PlanningModelCalls(properties, clock::get).execute(request, routed -> {
            budgets.add(routed.budget());
            if (budgets.size() == 1) { clock.set(Duration.ofSeconds(70).toNanos()); throw new LlmHttpException(503, "unavailable"); }
            return result;
        }, () -> {});
        assertEquals(List.of(Duration.ofSeconds(45), Duration.ofSeconds(20)), budgets);
    }

    @Test void operatorCanDisableFallbackAndUnconfiguredPrimaryCannotBorrowOtherRoutes() {
        properties.getPlanning().setMaxRouteAttempts(1);
        var count = new AtomicInteger();
        assertThrows(LlmHttpException.class, () -> new PlanningModelCalls(properties).execute(request, routed -> {
            count.incrementAndGet(); throw new LlmHttpException(503, "unavailable");
        }, () -> {}));
        assertEquals(1, count.get());
        properties.getPlanning().setMaxRouteAttempts(2);
        var unknown = new LlmClient.LlmRequest("openrouter", "unlisted", List.of(), List.of(), 0, request.budget());
        assertThrows(LlmHttpException.class, () -> new PlanningModelCalls(properties).execute(unknown, routed -> {
            count.incrementAndGet(); throw new LlmHttpException(503, "unavailable");
        }, () -> {}));
        assertEquals(2, count.get());
    }
}
