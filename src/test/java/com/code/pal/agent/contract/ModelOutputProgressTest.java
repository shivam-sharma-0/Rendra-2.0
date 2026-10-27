package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ModelOutputProgressTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AgentProperties properties = new AgentProperties();
    final List<ModelOutputProgress.Repeated> reports = new ArrayList<>();
    final ModelOutputProgress progress = new ModelOutputProgress(properties, mapper, reports::add);

    ModelOutputProgressTest() {
        properties.getProviders().put("mistral", new AgentProperties.Provider());
        properties.setBillingMode(AgentProperties.BillingMode.FREE);
        properties.getModels().setFreePlan(List.of("mistral:first", "nvidia:second", "openrouter:third"));
        properties.getModels().setFreeBuild(List.of("mistral:first", "nvidia:second"));
    }
    LlmClient.LlmRequest request(Object input) {
        return new LlmClient.LlmRequest("mistral", "first", List.of(LlmClient.LlmMessage.system("fixture"),
                LlmClient.LlmMessage.user(mapper.writeValueAsString(input))), List.of(), 0, Duration.ofSeconds(30))
                .withMaxTokens(1000).withJsonSchema(new LlmClient.JsonSchema("fixture", "{}"));
    }
    LlmClient.LlmResult result(String value) { return new LlmClient.LlmResult(value, null, List.of(), new LlmClient.Usage(3, 2), "stop"); }
    LlmClient.LlmResult call(CallPurpose purpose, LlmClient.LlmRequest request, Function<LlmClient.LlmRequest, LlmClient.LlmResult> physical) {
        try (var context = LlmCallContext.purpose(purpose, true)) { return progress.call(request, physical); }
    }
    Map<String, Object> repair(String failure) {
        return Map.of("unit", Map.of("id", "ui"), "repairFeedback", failure,
                "snapshot", Map.of("src/App.tsx", Map.of("version", "hash", "content", "export const count = 1")));
    }
    String files(String body) { return mapper.writeValueAsString(Map.of("files", List.of(Map.of("path", "src/App.tsx", "expectedVersion", "hash", "content", body)))); }

    @Test void identicalCorrectionRotatesOnceAndCannotCallTheStagnantRouteAgain() {
        var request = request(Map.of("id", "plan"));
        var routes = new ArrayList<String>();
        call(CallPurpose.UNIT, request, routed -> result("{\"bad\":true}"));
        var corrected = call(CallPurpose.UNIT_CORRECTION, request, routed -> {
            routes.add(routed.model());
            return result(routed.model().equals("first") ? "{ \"bad\" : true }" : "{\"fixed\":true}");
        });
        assertEquals("{\"fixed\":true}", corrected.content());
        assertEquals(List.of("first", "second"), routes);
        var stopped = assertThrows(LlmException.class, () -> call(CallPurpose.UNIT_CORRECTION, request, routed -> {
            routes.add(routed.model()); return result("{\"fixed\":true}");
        }));
        assertTrue(stopped.getMessage().contains("wastedCalls=2"));
        assertEquals(List.of("first", "second", "second"), routes);
    }

    @Test void bothRoutesRepeatingStopsWithoutAThirdRouteAndReportsEveryWastedCall() {
        var request = request(Map.of("id", "plan"));
        call(CallPurpose.UNIT, request, routed -> result("{}"));
        var calls = new AtomicInteger();
        assertThrows(LlmException.class, () -> call(CallPurpose.UNIT_CORRECTION, request, routed -> { calls.incrementAndGet(); return result("{}"); }));
        assertEquals(2, calls.get());
        assertEquals(2, progress.wastedCalls());
        assertEquals(List.of(1, 2), reports.stream().map(ModelOutputProgress.Repeated::wastedCalls).toList());
    }

    @Test void unchangedSourceRotatesOnTheFirstRepairWithDistinctAccountingAndProviderGrammar() {
        var request = request(repair("TS2307"));
        var identities = new ArrayList<UUID>();
        UUID initial = UUID.randomUUID();
        try (var identity = LlmLogicalCall.bind(initial)) {
            var response = call(CallPurpose.UNIT, request, routed -> {
                identities.add(LlmLogicalCall.currentOrNew());
                if (routed.model().equals("second")) {
                    assertNull(routed.jsonSchema());
                    assertTrue(routed.budget().toMillis() > 0);
                    assertTrue(routed.budget().compareTo(request.budget()) <= 0);
                    return result(files("export const count = 2"));
                }
                return result(files("export  const count=1"));
            });
            assertTrue(response.content().contains("count = 2"));
            assertEquals(initial, LlmLogicalCall.currentOrNew());
        }
        assertEquals(2, identities.size()); assertNotEquals(identities.getFirst(), identities.getLast());
        assertEquals(1, progress.wastedCalls());
    }

    @Test void differentFailureAndMeaningfulStringChangesDoNotReuseTheOldNoProgressState() {
        call(CallPurpose.UNIT, request(repair("first failure")), routed -> result(files("export const count = 2")));
        var calls = new AtomicInteger();
        call(CallPurpose.UNIT, request(repair("second failure")), routed -> { calls.incrementAndGet(); return result(files("export const count = 2")); });
        assertEquals(1, calls.get()); assertEquals(0, progress.wastedCalls());
        var request = request(Map.of("id", "strings"));
        call(CallPurpose.UNIT, request, routed -> result(files("export const label = 'a  b'")));
        call(CallPurpose.UNIT_CORRECTION, request, routed -> result(files("export const label = 'a b'")));
        assertEquals(0, progress.wastedCalls());
    }

    @Test void patchRepairAndBatchRepairBothDetectUnchangedEffectiveSource() {
        var patchInput = Map.of("batchId", "repair", "diagnosticsRevision", "rev", "diagnostics", List.of("TS2307"),
                "files", Map.of("src/App.tsx", Map.of("version", "hash", "content", "export const count = 1")));
        var patch = mapper.writeValueAsString(Map.of("patches", List.of(Map.of("path", "src/App.tsx", "edits",
                List.of(Map.of("search", "count = 1", "replace", "count=1"))))));
        var count = new AtomicInteger();
        assertThrows(LlmException.class, () -> call(CallPurpose.OTHER, request(patchInput), routed -> { count.incrementAndGet(); return result(patch); }));
        assertEquals(2, count.get());
        var batchInput = Map.of("units", List.of(Map.of("unit", Map.of("id", "ui"), "repairFeedback", "TS2307")),
                "snapshot", repair("TS2307").get("snapshot"));
        count.set(0);
        var batch = mapper.writeValueAsString(Map.of("units", List.of(Map.of("id", "ui", "files", mapper.readTree(files("export const count=1")).path("files")))));
        assertThrows(LlmException.class, () -> call(CallPurpose.UNIT, request(batchInput), routed -> { count.incrementAndGet(); return result(batch); }));
        assertEquals(2, count.get());
    }

    @Test void cancellationAndAccountingFailureOnTheAlternatePropagateWithoutAnotherCall() {
        for (var failure : List.of(new LlmCancelledException(), new LlmAccountingException("fixture"), new LlmQuotaExceededException("fixture"))) {
            var request = request(repair(failure.getClass().getSimpleName()));
            var count = new AtomicInteger();
            assertSame(failure, assertThrows(failure.getClass(), () -> call(CallPurpose.UNIT, request, routed -> {
                if (count.incrementAndGet() == 1) return result(files("export const count=1"));
                throw failure;
            })));
            assertEquals(2, count.get());
        }
    }

    @Test void anUnreachableAlternatePassesTheSingleAlternateTurnToTheNextConfiguredRoute() {
        // Live E2E 2026-10-27: the only alternate's key returned 403, so a repairable run stopped on no-progress.
        var routes = new ArrayList<String>();
        var fixed = call(CallPurpose.UNIT, request(repair("TS2304")), routed -> {
            routes.add(routed.model());
            return switch (routed.model()) {
                case "first" -> result(files("export const count=1"));
                case "second" -> throw new LlmHttpException(403, "Authorization failed");
                default -> result(files("export const count=2"));
            };
        });
        assertEquals(files("export const count=2"), fixed.content());
        assertEquals(List.of("first", "second", "third"), routes);
        var none = assertThrows(LlmException.class, () -> call(CallPurpose.UNIT, request(repair("other")), routed -> {
            if (routed.model().equals("first")) return result(files("export const count=1"));
            throw new LlmHttpException(503, "unavailable");
        }));
        assertTrue(none.getMessage().contains("alternate route unavailable"), none.getMessage());
    }

    @Test void lineNumberChangesDoNotAllowAnotherCallToTheStagnantRoute() {
        var routes = new ArrayList<String>();
        for (int line : List.of(1, 90)) {
            call(CallPurpose.UNIT, request(repair("{\"code\":\"TS2307\",\"line\":" + line + "}")), routed -> {
                routes.add(routed.model());
                return result(files(routed.model().equals("first") ? "export const count=1" : "export const count=" + line * 2));
            });
        }
        assertEquals(List.of("first", "second", "second"), routes);
        assertEquals(1, progress.wastedCalls());
    }

    @Test void operatorRouteBoundStopsCorrectionWithoutAddingACall() {
        properties.getModels().setFreeBuild(List.of("mistral:first"));
        properties.getModels().setFreePlan(List.of("mistral:first"));
        var request = request(Map.of("id", "plan"));
        call(CallPurpose.UNIT, request, routed -> result("{}"));
        var count = new AtomicInteger();
        assertThrows(LlmException.class, () -> call(CallPurpose.UNIT_CORRECTION, request, routed -> {
            count.incrementAndGet(); return result("{}");
        }));
        assertEquals(1, count.get());
    }
}
