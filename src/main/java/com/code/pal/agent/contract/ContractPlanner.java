package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.llm.*;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.agent.plan.PlanningModelCalls;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.capability.BuildDecisions;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.agent.workspace.ProjectPaths;

/** One logical planner invocation plus at most one schema correction. Caller owns durable call caps. */
@Component
@lombok.extern.slf4j.Slf4j
public final class ContractPlanner {
    private final ObjectMapper mapper;
    private final AgentProperties properties;

    public ContractPlanner(ObjectMapper mapper) {
        this(mapper, new AgentProperties());
    }

    @Autowired
    public ContractPlanner(ObjectMapper mapper, AgentProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    public record Input(UUID id, String request, String approvedRequirements, String projectMap,
            BuildDecisions.Choice choice, BuildContract.Limits limits, String requiredRootPath) {
        public Input(UUID id, String request, String approvedRequirements, String projectMap,
                BuildDecisions.Choice choice, BuildContract.Limits limits) {
            this(id, request, approvedRequirements, projectMap, choice, limits, null);
        }
    }

    public record SpecInput(Input base, ApprovedSpec spec, BatchBudget.Limits budget) {}

    public BuildContract plan(SpecInput input, AgentProperties.ModelSelection model,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall, Consumer<BuildContract> sources) {
        return new SpecContractPlanner(mapper).plan(input, model, checkedCall, sources);
    }

    public BuildContract plan(Input input, AgentProperties.ModelSelection model,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall) {
        return plan(input, model, checkedCall, contract -> {
        });
    }

    public BuildContract plan(Input input, AgentProperties.ModelSelection model,
            Function<LlmClient.LlmRequest, LlmClient.LlmResult> checkedCall, Consumer<BuildContract> sources) {
        boolean toolEnabled = properties.getPlanning().isContractToolEnabled();
        String format = PromptLoader.load(toolEnabled ? Prompt.CONTRACT_FORMAT_TOOL : Prompt.CONTRACT_FORMAT_JSON);
        String schema = SCHEMA.replace("{{format}}", format);
        schema = schema.replace("{{allowedEnvNames}}", String.join(", ", BuildContract.SUPPORTED_ENVIRONMENT_NAMES.stream().sorted().toList()));
        schema = schema.replace("{{freshMernPolicy}}", input.choice().storage() == BuildDecisions.Storage.MERN
                && input.requiredRootPath() != null ? PromptLoader.load(Prompt.CONTRACT_FRESH_MERN) : "");
        String system = PromptLoader.load(Prompt.CONTRACT_PLANNER_AUTHORITY).replace("{{schema}}", schema)
                .replace("{{runtimeCapabilities}}", PromptLoader.load(Prompt.RUNTIME_CAPABILITIES))
                .replace("{{authority}}", mapper.writeValueAsString(
                        Map.of("id", input.id(), "choice", input.choice(), "limits", input.limits())));
        String clientRoot = "client/src/rendra/" + input.id() + "/",
                serverRoot = "server/src/rendra/" + input.id() + "/";
        var interfaces = input.choice().storage() == BuildDecisions.Storage.MERN ? List.of(clientRoot + "types.ts",
                clientRoot + "api.ts", clientRoot + "theme.css", clientRoot + "routes.ts", serverRoot + "types.ts",
                serverRoot + "models.ts", serverRoot + "register.ts") : List.<String>of();
        if (input.choice().storage() == BuildDecisions.Storage.MERN && input.choice().auth() == BuildDecisions.Auth.ACCOUNTS) {
            interfaces = new ArrayList<>(interfaces);
            interfaces.add(clientRoot + "auth-client.ts");
        }
        String data = mapper.writeValueAsString(Map.of("request", input.request(), "approvedRequirements",
                input.approvedRequirements(), "projectMap", input.projectMap(), "requiredRootPath",
                input.requiredRootPath() == null ? "" : input.requiredRootPath(), "availableCoordinatorPaths",
                interfaces));
        if (system.length() + data.length() > Math.max(1, properties.getContextBudgetChars()))
            throw new IllegalArgumentException("Planner input exceeds bounded context");
        var messages = new ArrayList<LlmClient.LlmMessage>();
        messages.add(LlmClient.LlmMessage.system(system));
        messages.add(LlmClient.LlmMessage.user(data));
        List<LlmClient.ToolSpec> tools = List.of();
        if (toolEnabled) {
            var proposalSchema = ContractResponseSchema.planner(input, mapper);
            tools = List.of(new LlmClient.ToolSpec("proposeContract", PromptLoader.load(Prompt.CONTRACT_PROPOSAL_TOOL),
                    mapper.readValue(proposalSchema.schema(), Map.class)));
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            LlmClient.LlmResult result;
            try (var purpose = LlmCallContext
                    .purpose(attempt == 0 ? CallPurpose.PLANNER : CallPurpose.SCHEMA_CORRECTION, true)) {
                var selected = attempt == 0 ? model : new PlanningModelCalls(properties).schemaCorrectionRoute(model);
                var request = new LlmClient.LlmRequest(selected.provider(), selected.model(), List.copyOf(messages),
                        tools, 0.0,
                        Duration.ofMillis(input.limits().timeoutMs()), toolEnabled ? "required" : null)
                        .withMaxTokens(Math.max(1, properties.getPlanning().getContractOutputTokens()));
                if (!toolEnabled && properties.getPlanning().getSchemaProviders().contains(selected.provider()))
                    request = request.withJsonSchema(ContractResponseSchema.planner(input, mapper));
                result = checkedCall.apply(request);
            }
            String proposal = null;
            boolean validatingSources = false;
            try {
                if (result == null)
                    throw new IllegalArgumentException("Planner must return one bounded JSON object");
                if ("length".equals(result.finishReason()))
                    throw new IllegalArgumentException("Planner output reached its token limit; use concise objectives and avoid duplicate data");
                String body = result.content();
                if (result.hasToolCalls()) {
                    if (result.toolCalls().size() != 1 || !"proposeContract".equals(result.toolCalls().getFirst().name()))
                        throw new IllegalArgumentException("Planner may only propose one application contract");
                    body = result.toolCalls().getFirst().arguments();
                }
                if (body == null || body.length() > 48000)
                    throw new IllegalArgumentException("Planner must return one bounded JSON object");
                proposal = boundedProposal(body);
                body = ModelJson.unfenced(body);
                if (body.stripLeading().startsWith("[")) {
                    JsonNode wrapped = mapper.readerFor(JsonNode.class)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
                    log.info("planner_proposal_envelope toolCall={} items={} contractField={} unitField={}",
                            result.hasToolCalls(), wrapped.size(), wrapped.path(0).has("schemaVersion"), wrapped.path(0).has("objective"));
                    if (wrapped.size() != 1 || !wrapped.get(0).isObject())
                        throw new IllegalArgumentException("Planner must propose exactly one contract object");
                    body = mapper.writeValueAsString(wrapped.get(0));
                }
                String content = ModelJson.object(body);
                Map<?, ?> object = mapper.readerFor(Map.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readValue(content);
                var fields = object.keySet();
                if (!fields.equals(Set.of("schemaVersion", "id", "stack", "storage", "auth", "limits", "theme",
                        "routes", "types", "models", "api", "assets", "envNames", "units", "acceptance")))
                    throw new IllegalArgumentException("Contract fields are missing or unknown");
                content = mapper.writeValueAsString(object);
                content = ContractAcceptanceCompiler.completeCollections(content, mapper);
                // A parsed, bounded proposal lets the one correction repair the specific defect instead
                // of inventing a different plan with new defects. It remains untrusted data and is revalidated.
                proposal = boundedProposal(content);
                BuildContract contract = mapper.readValue(content, BuildContract.class);
                if (!contract.id().equals(input.id()) || !contract.limits().within(input.limits())
                        || contract.storage() != input.choice().storage() || contract.auth() != input.choice().auth())
                    throw new IllegalArgumentException("Contract cannot change approved capabilities/limits");
                if (input.requiredRootPath() != null && !contract.ownership().containsKey(input.requiredRootPath())
                        && contract.routes().isEmpty())
                    throw new IllegalArgumentException(
                            "New application needs an owned root component or a declared implemented route");
                verifyRequestedUi(input, contract);
                ApprovedProductScope.requirePages(input, contract, mapper);
                if (contract.units().size() + 1 > contract.limits().modelCalls())
                    throw new IllegalArgumentException("Units exceed the fixed model-call budget");
                validatingSources = true;
                sources.accept(contract);
                return contract;
            } catch (RuntimeException invalid) {
                if (validatingSources && !(invalid instanceof WorkflowStageException))
                    throw invalid;
                String reason = schemaFeedback(invalid);
                log.info("planner_contract_rejected attempt={} reason={}", attempt + 1, reason);
                if (attempt == 1)
                    throw new IllegalArgumentException(
                            "Planner schema correction failed: " + reason + "; no application files written", invalid);
                if (proposal != null)
                    messages.add(LlmClient.LlmMessage.user(PromptLoader.load(Prompt.CONTRACT_PROPOSAL_CORRECTION)
                            .replace("{{proposal}}", proposal)));
                messages.add(LlmClient.LlmMessage.user(PromptLoader.load(Prompt.CONTRACT_SCHEMA_CORRECTION)
                        .replace("{{reason}}", reason)));
            }
        }
        throw new IllegalStateException();
    }

    private String boundedProposal(String content) {
        if (content.length() <= 24000) return content;
        for (int fragment = 6000; fragment > 0; fragment /= 2) {
            String excerpt = mapper.writeValueAsString(Map.of("truncated", true, "prefix", content.substring(0, fragment),
                    "suffix", content.substring(content.length() - fragment)));
            if (excerpt.length() <= 24000) return excerpt;
        }
        throw new IllegalArgumentException("Contract correction exceeds bounded capacity");
    }

    private static void verifyRequestedUi(Input input, BuildContract contract) {
        String request = input.request().toLowerCase(Locale.ROOT);
        if (input.requiredRootPath() == null || contract.stack() != BuildContract.Stack.MERN
                || !request.matches("(?s).*\\b(add|create)\\b.*") || !request.matches("(?s).*\\bedit\\b.*")
                || !request.matches("(?s).*\\bdelete\\b.*"))
            return;
        var required = new LinkedHashMap<String, BehaviorCriteria.BrowserKind>();
        required.put("ui_add", BehaviorCriteria.BrowserKind.CLICK);
        required.put("ui_created", BehaviorCriteria.BrowserKind.ASSERT_TEXT);
        required.put("ui_edit", BehaviorCriteria.BrowserKind.CLICK);
        required.put("ui_edited", BehaviorCriteria.BrowserKind.ASSERT_TEXT);
        required.put("ui_delete", BehaviorCriteria.BrowserKind.CLICK);
        required.put("ui_deleted", BehaviorCriteria.BrowserKind.ASSERT_COUNT);
        required.put("ui_reload", BehaviorCriteria.BrowserKind.RELOAD);
        required.put("ui_persisted", BehaviorCriteria.BrowserKind.ASSERT_TEXT);
        if (request.contains("completed")) {
            required.put("ui_complete", BehaviorCriteria.BrowserKind.CLICK);
            required.put("ui_completed", BehaviorCriteria.BrowserKind.ASSERT_COUNT);
        }
        if (required.entrySet().stream().anyMatch(entry -> contract.acceptance().browser().stream()
                .noneMatch(action -> action.id().equals(entry.getKey()) && action.kind() == entry.getValue())))
            throw new IllegalArgumentException(
                    "Requested UI CRUD needs ui_add, ui_created, ui_edit, ui_edited, ui_delete, ui_deleted, ui_reload, ui_persisted and requested completion checks");
        var actions = contract.acceptance().browser();
        int add = -1, created = -1, edit = -1;
        for (int i = 0; i < actions.size(); i++) {
            switch (actions.get(i).id()) {
            case "ui_add" -> add = i;
            case "ui_created" -> created = i;
            case "ui_edit" -> edit = i;
            default -> {
            }
            }
        }
        boolean fillCreate = false, fillEdit = false;
        for (int i = 0; i < actions.size(); i++)
            if (actions.get(i).kind() == BehaviorCriteria.BrowserKind.FILL) {
                if (i < add)
                    fillCreate = true;
                if (i > created && i < edit)
                    fillEdit = true;
            }
        if (!fillCreate || !fillEdit)
            throw new IllegalArgumentException(
                    "UI create and edit must FILL actual inputs before ui_add and ui_edit; include a click to enter edit mode before its FILL");
    }

    /** Only backend-validator messages are safe; Jackson/provider messages can include model text. */
    static String schemaFeedback(Throwable failure) {
        String decoding = null;
        Set<String> fields = Set.of("schemaVersion", "id", "stack", "storage", "auth", "limits", "modelCalls",
                "toolCalls", "outputTokens", "timeoutMs", "theme", "routes", "types", "models", "api", "assets",
                "envNames", "units", "acceptance", "name", "fields", "kind", "optional", "path", "componentPath",
                "owner", "method", "requestType", "responseType", "routerPath", "routerExport", "basePath",
                "authenticated", "source", "destination", "fingerprint", "objective", "ownedPaths", "imports",
                "dependsOn", "acceptanceIds", "schema", "browser", "authRequired", "crudRequired", "selector", "value",
                "body", "status", "tokenVariable", "captureVariable", "capturePointer", "assertPointer", "assertValue",
                "files", "specId", "specRevision", "specHash", "planRevision", "templateVersion", "replacesContractId", "batches");
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            var trace = failure.getStackTrace();
            String origin = trace.length == 0 ? "" : trace[0].getClassName();
            if ((origin.startsWith(BuildContract.class.getName()) || origin.startsWith(BehaviorCriteria.class.getName())
                    || origin.equals(ContractPlanner.class.getName()) || origin.equals(ModelJson.class.getName())
                    || origin.equals(ContractStore.class.getName()) || origin.equals(SpecContractPlanner.class.getName())
                    || origin.equals(SpecPlanCompletion.class.getName()) || origin.equals(SpecPlanValues.class.getName())
                    || origin.equals(ContractPlans.class.getName()) || origin.equals(ContractBatch.class.getName())
                    || origin.equals(ProjectPaths.class.getName()) || origin.equals(ContractImports.class.getName())
                    || origin.equals(UnstoredUnitSplit.class.getName()) || origin.equals(SpecAcceptance.class.getName()))
                    && (failure instanceof IllegalArgumentException || failure instanceof WorkflowStageException)
                    && failure.getMessage() != null
                    && failure.getMessage().matches("[A-Za-z0-9 /,;:.()\\[\\]_-]{1,180}"))
                return failure.getMessage();
            if (failure instanceof JacksonException json) {
                String field = json.getPath().stream().limit(8)
                        .map(reference -> reference.getPropertyName() != null
                                && fields.contains(reference.getPropertyName()) ? reference.getPropertyName() : "field")
                        .collect(Collectors.joining("."));
                decoding = "JSON " + (field.isEmpty() ? "object" : "field " + field)
                        + " has an invalid format or type (" + json.getClass().getSimpleName() + ")";
            }
        }
        return decoding == null ? "Contract JSON fields or primitive types are invalid" : decoding;
    }

    private static final String SCHEMA = PromptLoader.load(Prompt.CONTRACT_PLANNER);
}
