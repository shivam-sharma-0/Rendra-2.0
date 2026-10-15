package com.code.pal.agent.contract;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.SpecFixtures;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SpecPlannerReliabilityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SpecPlanningTest fixture = new SpecPlanningTest();
    private final AgentProperties.ModelSelection model = new AgentProperties.ModelSelection("mistral", "synthetic-codestral");
    private static final String DRAFT = """
            {"units":[{"id":"app","objective":"Implement the approved bakery",
            "ownedPaths":["./index.html","styles.css"],"imports":null,"dependsOn":null}]}
            """;

    @Test
    void messyUnitsOnlyReplyIsCompletedFromApprovalOnTheFirstCall() {
        var spec = SpecFixtures.approved();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        var calls = new AtomicInteger();
        var contract = new ContractPlanner(mapper).plan(input, model, request -> {
            calls.incrementAndGet();
            var schema = mapper.readTree(request.jsonSchema().schema());
            assertEquals(List.of("units"), mapper.convertValue(schema.path("required"), List.class));
            assertEquals(1, schema.path("properties").size());
            assertTrue(request.jsonSchema().schema().length() < 3_000);
            assertTrue(request.messages().getLast().content().contains("requiredPageFiles"));
            return reply("Here is the implementation layout:\n```json\n" + DRAFT + "\n```\nReady to implement.");
        }, ignored -> {});
        assertEquals(1, calls.get());
        assertEquals(input.base().id(), contract.id());
        assertEquals(spec.specHash(), contract.specHash());
        assertEquals(List.of("/"), contract.routes().stream().map(BuildContract.Route::path).toList());
        assertEquals("index.html", contract.routes().getFirst().componentPath());
        assertEquals(List.of("heading_0"), contract.units().getFirst().acceptanceIds());
        assertEquals("Solar Bakery", contract.acceptance().browser().getFirst().value());
        assertEquals("#f80", contract.theme().get("accent"));
        assertEquals(List.of("index.html", "styles.css"), contract.batches().getFirst().ownedPaths());
    }

    @Test
    void missingRootStylesAndUnitMetadataAreCompletedWithoutAnotherCall() {
        var spec = SpecFixtures.browser();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        var contract = new ContractPlanner(mapper).plan(input, model,
                request -> reply("{\"units\":[{\"files\":[\"src/index.css\"]}]}"), ignored -> {});
        assertTrue(contract.ownership().keySet().containsAll(List.of("src/App.tsx", "src/index.css")));
        assertEquals("app", contract.routes().getFirst().owner());
        assertEquals(spec.desired().storage().name(), contract.storage().name());
        assertEquals("task", contract.types().getFirst().name());
        SpecContractPlanner.verifyCoverage(spec, contract);
    }

    @Test
    void multipleUnitsDeriveRoutesDependenciesAcceptanceAndBatches() {
        var spec = new DeterministicContentPlanTest().twoPages(ApprovedSpec.RenderStack.VITE);
        var input = fixture.input(spec, fixture.deterministic(SpecFixtures.approved()).id());
        String draft = """
                {"units":[
                  {"id":"home","ownedPaths":["src/pages/home.tsx"],"imports":["src/shared.ts"]},
                  {"id":"about","ownedPaths":["src/pages/about.tsx","src/shared.ts"]}
                ]}
                """;
        var contract = new ContractPlanner(mapper).plan(input, model, request -> reply(draft), ignored -> {});
        assertEquals(List.of("/", "/about"), contract.routes().stream().map(BuildContract.Route::path).toList());
        assertEquals(List.of("about"), contract.units().getFirst().dependsOn());
        assertFalse(contract.units().get(1).acceptanceIds().isEmpty());
        assertEquals(2, contract.waves().size());
        SpecContractPlanner.verifyCoverage(spec, contract);
        ContractPlans.validateBatches(contract.units(), contract.batches(), contract.limits());
    }

    @Test
    void correctionContainsThePreciseErrorAndEachRejectionIsLoggedWithoutTheReply() {
        Logger logger = (Logger) LoggerFactory.getLogger(SpecContractPlanner.class);
        var appender = new ListAppender<ILoggingEvent>();
        Level original = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            var spec = SpecFixtures.approved();
            var input = fixture.input(spec, fixture.deterministic(spec).id());
            var calls = new AtomicInteger();
            var contract = new ContractPlanner(mapper).plan(input, model, request -> {
                if (calls.incrementAndGet() == 1) {
                    return reply("{\"units\":[]}");
                }
                assertTrue(request.messages().getLast().content().contains("Implementation units must contain 1 to 12 items"));
                return reply(DRAFT);
            }, ignored -> {});
            assertNotNull(contract);
            assertEquals(2, calls.get());
            var events = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertTrue(events.stream().anyMatch(message -> message.contains("planner_contract_rejected attempt=1")
                    && message.contains("Implementation units must contain 1 to 12 items")));
            appender.list.clear();
            var failure = assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input, model,
                    request -> reply("{\"units\":[]}"), ignored -> fail("No invalid proposal may persist")));
            assertTrue(failure.getMessage().contains("Implementation units must contain 1 to 12 items"));
            assertEquals(2, appender.list.size());
            assertTrue(appender.list.getLast().getFormattedMessage().contains("attempt=2"));
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("\"units\"")));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(original);
            appender.stop();
        }
    }

    @Test
    void duplicateOwnershipProtectedPathsAndAmbiguousJsonNeverPersist() {
        var spec = SpecFixtures.approved();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        for (String invalid : List.of(
                "{\"units\":[{\"id\":\"one\",\"ownedPaths\":[\"index.html\"]},{\"id\":\"two\",\"ownedPaths\":[\"index.html\"]}]}",
                "{\"units\":[{\"ownedPaths\":[\".env\"]}]}", DRAFT + DRAFT,
                "{\"units\":[{\"ownedPaths\":[\"index.html\"],\"command\":\"unsafe\"}]}")) {
            var calls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input, model, request -> {
                calls.incrementAndGet();
                return reply(invalid);
            }, ignored -> fail("No invalid proposal may persist")));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void leanReplyCannotGrantAccountsOrReplaceApprovedAcceptance() {
        var spec = SpecFixtures.approved();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        for (String field : List.of("auth", "acceptance", "limits")) {
            String invalid = mapper.writeValueAsString(Map.of("units", List.of(Map.of("ownedPaths", List.of("index.html"))), field, "invented"));
            assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input, model,
                    request -> reply(invalid), ignored -> fail("No capability override")));
        }
    }

    @Test
    void approvedAssetFingerprintAndDestinationOwnershipAreCompletedByCode() {
        var original = SpecFixtures.approved();
        var body = original.spec();
        var asset = new ApprovedSpec.Asset("uploads/logo.png", SpecFixtures.HASH, "Bakery logo");
        var assets = List.of(asset, new ApprovedSpec.Asset("uploads/menu.jpg", SpecFixtures.HASH, "Menu photo"),
                new ApprovedSpec.Asset("uploads/hero.webp", SpecFixtures.HASH, "Bakery photo"));
        var spec = SpecFixtures.envelope(new ApprovedSpec.Spec(body.title(), body.language(), body.goal(), body.pages(), body.entities(),
                body.features(), body.theme(), body.acceptance(), assets, body.constraints()), original.desired(), original.readiness());
        var input = fixture.input(spec, fixture.deterministic(original).id());
        var actual = new ContractPlanner(mapper).plan(input, model, request -> reply(DRAFT), ignored -> {});
        var planned = actual.assets().getFirst();
        assertEquals(asset.projectPath(), planned.source());
        assertEquals(asset.fingerprint(), planned.fingerprint());
        assertEquals("assets/aaaaaaaaaaaa-logo.png", planned.destination());
        assertEquals(planned.owner(), actual.ownership().get(planned.destination()));
        assertEquals(3, actual.assets().size(), "Copied binary assets must not consume generated-source token estimates");
    }

    @Test
    void oversizedAtomicDraftIsSplitWithoutACorrectionOrRaisedBudget() {
        var source = SpecFixtures.approved();
        var spec = SpecFixtures.envelope(source.spec(), new ApprovedSpec.Desired(ApprovedSpec.RenderStack.VITE,
                ApprovedSpec.Storage.NONE, ApprovedSpec.Auth.NONE), source.readiness());
        var input = fixture.input(spec, fixture.deterministic(source).id());
        var calls = new AtomicInteger();
        var actual = new ContractPlanner(mapper).plan(input, model, request -> {
            calls.incrementAndGet();
            return reply("{\"units\":[{\"id\":\"app\",\"ownedPaths\":[\"src/App.tsx\",\"src/One.tsx\",\"src/Two.tsx\"]}]}");
        }, ignored -> {});
        assertEquals(1, calls.get());
        assertTrue(actual.batches().size() > 1);
        assertTrue(actual.batches().stream().allMatch(batch -> batch.outputCap() <= input.budget().outputCap()));
        assertTrue(actual.limits().within(input.base().limits()));
        SpecContractPlanner.verifyCoverage(spec, actual);
    }

    @Test
    void truncatedOrToolBearingReplyNeverBecomesACompletedContract() {
        var spec = SpecFixtures.approved();
        var input = fixture.input(spec, fixture.deterministic(spec).id());
        var truncated = new LlmClient.LlmResult(DRAFT, null, List.of(), null, "length");
        var tools = new LlmClient.LlmResult(DRAFT, null, List.of(new LlmClient.ToolCall("x", "writeFile", "{}")), null, "stop");
        for (var invalid : List.of(truncated, tools)) {
            var calls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> new ContractPlanner(mapper).plan(input, model, request -> {
                calls.incrementAndGet();
                return invalid;
            }, ignored -> fail("No partial or tool-bearing contract may persist")));
            assertEquals(2, calls.get());
        }
    }

    private LlmClient.LlmResult reply(String text) {
        return new LlmClient.LlmResult(text, null, List.of(), new LlmClient.Usage(10, 20), "stop");
    }
}
