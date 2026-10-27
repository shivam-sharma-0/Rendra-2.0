package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class PlannerBatchHarnessTest {
    final ObjectMapper mapper = new ObjectMapper();

    @Test void rawAndNormalizedChecksUseTheRealAuthCompilerAndContractValidatorOffline() {
        var contract = ContractFixtures.accounts();
        var planner = new ContractPlanner(mapper);
        var input = new ContractPlanner.Input(contract.id(), "Items", "Approved", "Map",
                new BuildDecisions.Choice(1, contract.storage(), contract.auth(), null), contract.limits());
        Map<String, Object> proposal = mapper.readValue(mapper.writeValueAsString(contract), Map.class);
        var acceptance = (Map<String, Object>) proposal.get("acceptance");
        var api = (List<Map<String, Object>>) acceptance.get("api");
        var removed = new HashSet<Object>();
        api.removeIf(step -> step.get("kind").toString().startsWith("AUTH_") && removed.add(step.get("id")));
        // A model that omits auth setup also omits its ids from unit ownership.
        for (var unit : (List<Map<String, Object>>) proposal.get("units"))
            ((List<Object>) unit.get("acceptanceIds")).removeIf(removed::contains);
        var response = new LlmClient.LlmResult(mapper.writeValueAsString(proposal), null, List.of(), new LlmClient.Usage(10, 20), "stop");
        assertEquals(false, PlannerBatchHarness.inspect(planner, input, response, false).get("valid"));
        var normalized = PlannerBatchHarness.inspect(planner, input, response, true);
        assertEquals(true, normalized.get("valid"), normalized::toString);
        assertTrue(((List<String>) normalized.get("normalizationEvents")).stream().anyMatch(event -> event.contains("auth")));
    }

    @Test void requestPreparationNeverCallsAProviderOrEntersCorrection() {
        var contract = ContractFixtures.mern();
        var properties = new AgentProperties();
        var input = new ContractPlanner.Input(contract.id(), "Items", "Approved", "Map",
                new BuildDecisions.Choice(1, contract.storage(), contract.auth(), null), contract.limits());
        var request = PlannerBatchHarness.prepare(new ContractPlanner(mapper, properties), input,
                new AgentProperties.ModelSelection("mistral", "codestral-latest"));
        assertEquals(2, request.messages().size()); assertEquals(12288, request.maxTokens());
        assertEquals("Items", mapper.readTree(request.messages().getLast().content()).path("request").asString());
        assertEquals(CallPurpose.OTHER, LlmCallContext.current().purpose());
    }

    @Test void batchCountsFirstProposalsAndSeparatesProviderFailuresFromValidation() {
        var report = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> samples = List.of(Map.of("outcome", "VALID", "normalized", Map.of("valid", true)),
                Map.of("outcome", "REJECTED", "normalized", Map.of("valid", false)),
                Map.of("outcome", "RATE_LIMIT"), Map.of("outcome", "PROVIDER_FAILURE"));
        PlannerBatchHarness.summarize(report, samples);
        assertEquals(2L, report.get("proposals")); assertEquals(1L, report.get("validAfterNormalization"));
        assertEquals(50.0, report.get("normalizedValidityPercent"));
        assertEquals(1L, report.get("rateLimitSamples")); assertEquals(1L, report.get("providerFailureSamples"));
        assertTrue(PlannerBatchHarness.rateLimited(RetryableLlmException.rateLimited(0)));
        assertTrue(PlannerBatchHarness.rateLimited(new LlmStreamException(LlmStreamException.Reason.PROVIDER_ERROR, 429, null)));
        assertFalse(PlannerBatchHarness.rateLimited(new LlmHttpException(503, "unavailable")));
    }

    @Test void replayRevalidatesSavedProposalsWithTheSavedContractIdAndCarriesOverFailedCalls(@TempDir Path batch) throws Exception {
        var contract = ContractFixtures.accounts();
        Map<String, Object> proposal = mapper.readValue(mapper.writeValueAsString(contract), Map.class);
        var removed = new HashSet<Object>();
        ((List<Map<String, Object>>) ((Map<String, Object>) proposal.get("acceptance")).get("api"))
                .removeIf(step -> step.get("kind").toString().startsWith("AUTH_") && removed.add(step.get("id")));
        for (var unit : (List<Map<String, Object>>) proposal.get("units"))
            ((List<Object>) unit.get("acceptanceIds")).removeIf(removed::contains);
        // A fresh accounts app needs a sign-in page.
        ((List<Object>) proposal.get("units")).add(Map.of("id", "login", "objective", "Sign in", "ownedPaths", List.of("client/src/pages/Login.tsx"),
                "imports", List.of(), "dependsOn", List.of(), "acceptanceIds", List.of()));
        ((List<Object>) proposal.get("routes")).add(Map.of("path", "/login", "componentPath", "client/src/pages/Login.tsx", "owner", "login"));
        var usage = new LlmClient.Usage(10, 20);
        Files.writeString(batch.resolve("fixture.json"), "{\"cases\":[{\"name\":\"items\",\"repeats\":2,\"prompt\":\"Items\"}]}");
        Files.writeString(batch.resolve("01-items-1-requirements.json"), mapper.writeValueAsString(new LlmClient.LlmResult(
                "```json\n{\"questions\":[],\"spec\":{\"title\":\"Items\",\"pages\":[],\"features\":[]}}\n```", null, List.of(), usage, "stop")));
        Files.writeString(batch.resolve("01-items-1-planner.json"), mapper.writeValueAsString(
                new LlmClient.LlmResult(mapper.writeValueAsString(proposal), null, List.of(), usage, "stop")));
        Files.writeString(batch.resolve("report.json"), mapper.writeValueAsString(Map.of("samples", List.of(
                Map.of("id", "01-items-1", "case", "items", "requirements", Map.of("status", "OK"), "contractId", contract.id(),
                        "planning", Map.of("status", "OK"), "normalized", Map.of("valid", false, "signature", "old"), "outcome", "REJECTED"),
                Map.of("id", "02-items-2", "case", "items", "requirements", Map.of("status", "OK"),
                        "planning", Map.of("status", "RATE_LIMIT"), "outcome", "RATE_LIMIT")))));
        Path output = Files.createDirectories(batch.resolve("replay"));

        var report = PlannerBatchHarness.replay(mapper, batch, batch.resolve("fixture.json"), output);

        assertEquals(batch.toString(), report.get("replayOf")); assertEquals(0, report.get("correctionCalls"));
        assertEquals(2, report.get("attemptedSamples")); assertEquals(1L, report.get("proposals"));
        assertEquals(1L, report.get("validAfterNormalization")); assertEquals(1L, report.get("rateLimitSamples"));
        var samples = (List<Map<String, Object>>) report.get("samples");
        var replayed = samples.getFirst();
        assertEquals("VALID", replayed.get("outcome"));
        assertEquals(Map.of("outcome", "REJECTED", "signature", "old"), replayed.get("original"));
        assertEquals(false, ((Map<String, Object>) replayed.get("raw")).get("valid"));
        assertTrue(((List<String>) ((Map<String, Object>) replayed.get("normalized")).get("normalizationEvents")).stream()
                .anyMatch(event -> event.startsWith("acceptance_auth_setup_inserted")));
        assertEquals(true, ((Map<String, Object>) replayed.get("requirements")).get("draftCaptured"));
        assertEquals("RATE_LIMIT", samples.getLast().get("outcome")); assertFalse(samples.getLast().containsKey("normalized"));
        assertEquals(report.get("validAfterNormalization"), mapper.readTree(Files.readString(output.resolve("report.json")))
                .path("validAfterNormalization").asLong());
    }

    @Test void shippedBatchContainsDetailedAndShortNaturalPrompts() throws Exception {
        var cases = mapper.readTree(Files.readString(Path.of("scripts/fixtures/planner-batch.json"))).path("cases");
        assertEquals(7, cases.size()); assertEquals(10, cases.get(0).path("repeats").asInt());
        int count = 0, shortPrompts = 0;
        for (var item : cases) {
            count += item.path("repeats").asInt();
            if (item.path("name").asString().startsWith("short-")) shortPrompts++;
        }
        assertEquals(25, count); assertEquals(3, shortPrompts);
    }
}
