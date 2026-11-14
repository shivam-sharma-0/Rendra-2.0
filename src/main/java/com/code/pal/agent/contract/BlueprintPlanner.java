package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.CallPurpose;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmLogicalCall;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.project.capability.BuildDecisions;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

@Slf4j
public final class BlueprintPlanner {
    private static final int OUTPUT_TOKENS = 6000;
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public BlueprintPlanner(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public static boolean supports(AgentProperties properties, BuildDecisions.Choice choice, String requiredRootPath) {
        return "smart".equalsIgnoreCase(properties.getEngine()) && requiredRootPath != null
                && choice.storage() != BuildDecisions.Storage.SUPABASE
                && (choice.storage() == BuildDecisions.Storage.MERN || choice.auth() != BuildDecisions.Auth.ACCOUNTS);
    }

    public BuildContract plan(ContractPlanner.Input input, AgentProperties.ModelSelection model,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall) {
        var choice = input.choice();
        boolean mern = choice.storage() == BuildDecisions.Storage.MERN;
        String system = PromptLoader.load(Prompt.BLUEPRINT_PLANNER)
                .replace("{{storage}}", mern ? "app server with a database" : "the person's browser")
                .replace("{{auth}}", choice.auth() == BuildDecisions.Auth.ACCOUNTS ? "on (sign up, sign in, roles)" : "off")
                .replace("{{storageNote}}", mern ? "Entities are saved on the server." : choice.storage() == BuildDecisions.Storage.NONE
                        ? "Keep entities to what the app truly needs to remember; they are saved in the browser."
                        : "Entities are saved in the browser and there are no accounts, so access is ignored.")
                .replace("{{presets}}", String.join(", ", DesignPresets.NAMES))
                .replace("{{icons}}", String.join(", ", new TreeSet<>(Blueprint.ICONS)));
        var data = new LinkedHashMap<String, Object>();
        data.put("request", input.request());
        data.put("approvedRequirements", input.approvedRequirements());
        var messages = new ArrayList<LlmClient.LlmMessage>(List.of(LlmClient.LlmMessage.system(system),
                LlmClient.LlmMessage.user(mapper.writeValueAsString(data))));
        var signIn = signInPaths(input);
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmClient.LlmResult result;
            try (var purpose = LlmCallContext.purpose(attempt == 0 ? CallPurpose.PLANNER : CallPurpose.SCHEMA_CORRECTION, true);
                    var identity = LlmLogicalCall.bind(UUID.randomUUID())) {
                result = checkedCall.apply(new LlmClient.LlmRequest(model.provider(), model.model(), List.copyOf(messages), List.of(),
                        0.2, Duration.ofMillis(input.limits().timeoutMs()), null)
                        .withMaxTokens(Math.max(OUTPUT_TOKENS, properties.getPlanning().getContractOutputTokens())));
            }
            try {
                if (result == null || result.content() == null)
                    throw new IllegalArgumentException("Reply with one JSON object");
                if ("length".equals(result.finishReason()))
                    throw new IllegalArgumentException("The blueprint was cut off; keep descriptions short and move extras to later");
                Map<?, ?> raw = mapper.readerFor(Map.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(ModelJson.extractObject(result.content()));
                var blueprint = BlueprintCompiler.normalize(raw, approvedPaths(input));
                var contract = BlueprintCompiler.compile(blueprint, input.id(), choice, input.limits(), signIn);
                ApprovedProductScope.requirePages(input, contract, mapper);
                if (contract.units().stream().filter(unit -> unit.id().startsWith("page_")).count() + 1 > input.limits().modelCalls())
                    throw new IllegalArgumentException("Too many written pages for this build; move some to later");
                log.info("blueprint_planned contract={} entities={} pages={} later={}", contract.id(), blueprint.entities().size(),
                        blueprint.pages().size(), blueprint.later().size());
                return contract;
            } catch (RuntimeException invalid) {
                String reason = Objects.toString(invalid.getMessage(), invalid.getClass().getSimpleName());
                log.info("blueprint_rejected attempt={} reason={}", attempt + 1, reason);
                if (attempt == 1)
                    throw new IllegalArgumentException("The app blueprint could not be planned: " + reason + "; no application files written", invalid);
                if (result != null && result.content() != null)
                    messages.add(LlmClient.LlmMessage.assistant(result.content().length() > 24_000
                            ? result.content().substring(0, 24_000) : result.content()));
                messages.add(LlmClient.LlmMessage.user("That blueprint was rejected: " + reason + ". Written pages (landing, dashboard,"
                        + " settings, custom) are capped at " + Blueprint.AGENT_PAGES + " and approved paths are kept first; show records"
                        + " on generated list pages and move optional screens to later"
                        + ". Reply with the whole corrected JSON object only."));
            }
        }
        throw new IllegalStateException();
    }

    private Set<String> approvedPaths(ContractPlanner.Input input) {
        var paths = new HashSet<String>();
        try {
            JsonNode approved = input.approvedRequirements() == null ? null : mapper.readTree(input.approvedRequirements());
            if (approved != null)
                for (var page : approved.path("requestedScope").path("pages"))
                    paths.add(page.path("path").asString(""));
        } catch (RuntimeException unreadable) {
            log.info("blueprint_requirements_unreadable reason={}", unreadable.getClass().getSimpleName());
        }
        paths.remove("");
        return Set.copyOf(paths);
    }

    private List<String> signInPaths(ContractPlanner.Input input) {
        var paths = new ArrayList<String>();
        try {
            JsonNode approved = input.approvedRequirements() == null ? null : mapper.readTree(input.approvedRequirements());
            if (approved != null)
                for (var page : approved.path("requestedScope").path("pages")) {
                    String path = page.path("path").asString("");
                    if (path.matches("/[A-Za-z0-9_/.-]{1,120}") && ApprovedProductScope.signInRoute(path) && !paths.contains(path))
                        paths.add(path);
                }
        } catch (RuntimeException unreadable) {
            log.info("blueprint_requirements_unreadable reason={}", unreadable.getClass().getSimpleName());
        }
        return paths.isEmpty() ? List.of("/sign-in") : List.copyOf(paths);
    }
}
