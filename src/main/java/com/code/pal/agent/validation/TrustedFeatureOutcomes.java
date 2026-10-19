package com.code.pal.agent.validation;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.contract.ContractStore;
import com.code.pal.agent.prompt.PromptLoader;
import com.code.pal.agent.prompt.PromptLoader.Prompt;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Backend-owned outcome templates and random browser fixtures, separate from model-authored scenario assertions. */
@Service
public final class TrustedFeatureOutcomes {
    private static final Set<String> PHASES = Set.of("ready", "prepare", "submit", "feedback", "created", "edit", "replace",
            "updated", "delete", "deleted", "reload", "reload_created", "reload_updated", "reload_deleted");
    public record Definition(String id, String kind, String field) {}
    public record Plan(String binding, List<Definition> definitions, List<String> uncovered) {
        public static final Plan EMPTY = new Plan("", List.of(), List.of());
        public Plan { definitions = List.copyOf(definitions); uncovered = List.copyOf(uncovered); }
    }

    private final ApprovedProjectMemory memory;
    private final BuildDecisions decisions;
    private final AgentProperties properties;
    private final ObjectMapper mapper;

    public TrustedFeatureOutcomes(ApprovedProjectMemory memory, BuildDecisions decisions, AgentProperties properties, ObjectMapper mapper) {
        this.memory = memory;
        this.decisions = decisions;
        this.properties = properties;
        this.mapper = mapper;
    }

    public Plan plan(UUID project) {
        if (!properties.getFeatureOutcomes().isEnabled()) return Plan.EMPTY;
        var approved = memory.current(project);
        var state = decisions.state(project);
        if (approved == null || approved.specJson() == null || state == null || state.blocked() || state.choice() == null) {
            return new Plan("native-outcomes-v1:unavailable:" + properties.getFeatureOutcomes().getBrowserBudgetMs() + ":"
                    + properties.getFeatureOutcomes().getStepTimeoutMs(), List.of(), List.of("CURRENT_APPROVED_SCOPE"));
        }
        String binding = "native-outcomes-v1:" + approved.projectionId() + ":" + approved.revision() + ":"
                + ContractStore.hash(approved.specJson()) + ":" + state + ":" + properties.getFeatureOutcomes().getBrowserBudgetMs()
                + ":" + properties.getFeatureOutcomes().getStepTimeoutMs();
        var draft = mapper.readTree(approved.specJson());
        var scope = draft.has("requestedScope") ? draft.path("requestedScope") : draft;
        if (draft.path("answersChanged").asBoolean(false) || !scope.path("features").isArray() || scope.path("features").size() > 24) {
            return new Plan(binding, List.of(), List.of("CURRENT_APPROVED_SCOPE"));
        }
        var definitions = new ArrayList<Definition>();
        var uncovered = new ArrayList<String>();
        var ids = new HashSet<String>();
        for (JsonNode feature : scope.path("features")) {
            String id = feature.path("id").asString("");
            if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,47}") || !ids.add(id)) {
                return new Plan(binding, List.of(), List.of("AMBIGUOUS_APPROVED_FEATURES"));
            }
            String kind = feature.path("kind").asString("");
            if (kind.equals("CONTACT_FORM") && List.of(BuildDecisions.Storage.NONE, BuildDecisions.Storage.BROWSER).contains(state.choice().storage())) {
                definitions.add(new Definition(id, "FORM_FEEDBACK", null));
            } else if (kind.equals("LOCAL_CRUD") && state.choice().storage() == BuildDecisions.Storage.BROWSER) {
                String field = editableField(scope, feature);
                if (field == null) uncovered.add(id);
                else definitions.add(new Definition(id, "BROWSER_CRUD", field));
            } else uncovered.add(id);
        }
        return new Plan(binding, definitions, uncovered);
    }

    private static String editableField(JsonNode scope, JsonNode feature) {
        var references = feature.path("entityIds");
        if (!references.isArray() || references.size() != 1 || !scope.path("entities").isArray()) return null;
        String entityId = references.get(0).asString("");
        var entities = new ArrayList<JsonNode>();
        scope.path("entities").forEach(entity -> { if (entity.path("id").asString("").equals(entityId)) entities.add(entity); });
        if (entities.size() != 1 || !entities.getFirst().path("fields").isArray()) return null;
        for (JsonNode field : entities.getFirst().path("fields")) {
            String name = field.path("name").asString("");
            if (field.path("kind").asString("").equalsIgnoreCase("STRING") && name.matches("[A-Za-z][A-Za-z0-9_]{0,47}")
                    && !List.of("id", "user_id", "created_at", "updated_at").contains(name)) return name;
        }
        return null;
    }

    public String guidance(Plan plan) {
        if (plan.definitions().isEmpty()) return "";
        String json = mapper.writeValueAsString(plan.definitions()).replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        return PromptLoader.load(Prompt.FEATURE_OUTCOMES).replace("{{definitions}}", json);
    }

    public List<ValidationCheck> checks(Plan plan, JsonNode report) {
        var result = new ArrayList<ValidationCheck>();
        if (!plan.uncovered().isEmpty()) {
            result.add(new ValidationCheck("FEATURE_COVERAGE", ValidationCheck.Outcome.SKIPPED, false,
                    "Independent outcome templates do not cover approved features: " + String.join(",", plan.uncovered())));
        }
        if (plan.definitions().isEmpty()) return List.copyOf(result);
        var outcomes = report == null ? mapper.createArrayNode() : report.path("outcomes");
        boolean complete = outcomes.isArray() && outcomes.size() == plan.definitions().size();
        if (complete) for (int index = 0; index < plan.definitions().size(); index++) {
            JsonNode evidence = outcomes.get(index);
            complete &= evidence.path("id").asString("").equals(plan.definitions().get(index).id())
                    && List.of("PASS", "FAIL", "SKIPPED").contains(evidence.path("outcome").asString(""));
        }
        for (int index = 0; index < plan.definitions().size(); index++) {
            Definition definition = plan.definitions().get(index);
            var outcome = complete ? ValidationCheck.Outcome.valueOf(outcomes.get(index).path("outcome").asString()) : ValidationCheck.Outcome.SKIPPED;
            String scope = definition.kind().equals("FORM_FEEDBACK")
                    ? "Native form submission and visible success feedback (delivery is not certified)"
                    : "Random-fixture browser create/update/delete and reload persistence";
            String phase = complete ? outcomes.get(index).path("phase").asString("") : "";
            String reason = complete ? outcomes.get(index).path("reason").asString("") : "";
            String detail = switch (reason) {
            case "FEATURE_ROOT_MISSING" -> "expected one visible native container with data-rendra-feature=" + definition.id()
                    + "; route-only features need a visible internal data-rendra-feature-link=" + definition.id();
            case "FEATURE_ROOT_AMBIGUOUS" -> "multiple visible feature containers share the same id";
            case "NATIVE_FORM_MISSING" -> "the feature container needs one visible native form";
            case "NATIVE_FORM_AMBIGUOUS" -> "the feature container has multiple visible native forms";
            case "FIELD_MISSING", "FIELD_AMBIGUOUS", "FIELD_NOT_EDITABLE" -> "the form needs exactly one visible, enabled, editable text input or textarea with name=" + definition.field()
                    + "; an id or placeholder alone does not provide this name";
            case "SUBMIT_BUTTON_AMBIGUOUS_OR_MISSING" -> "the form needs exactly one visible, enabled submit button";
            case "FORM_INVALID" -> "the native form rejects valid generated input; inspect required controls and validation constraints";
            case "EDIT_BUTTON_AMBIGUOUS_OR_MISSING" -> "each saved row needs one visible enabled button with data-rendra-action=edit that populates the same form";
            case "DELETE_BUTTON_AMBIGUOUS_OR_MISSING" -> "each saved row needs one visible enabled button with data-rendra-action=delete";
            default -> "";
            };
            result.add(new ValidationCheck("FEATURE_" + definition.id(), outcome, true,
                    scope + (outcome == ValidationCheck.Outcome.PASS ? " passed" : outcome == ValidationCheck.Outcome.FAIL ? " did not pass"
                            : " coverage was unavailable") + (outcome != ValidationCheck.Outcome.PASS && PHASES.contains(phase) ? "; phase=" + phase : "")
                            + (outcome != ValidationCheck.Outcome.PASS && !detail.isEmpty() ? "; " + detail : "")));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> browserData(Plan plan, List<BehaviorCriteria.BrowserAction> actions) {
        return Map.of("schema", 1, "actions", actions, "outcomes", plan.definitions(),
                "outcomeBudgetMs", Math.max(1, properties.getFeatureOutcomes().getBrowserBudgetMs()),
                "outcomeStepTimeoutMs", Math.max(1, properties.getFeatureOutcomes().getStepTimeoutMs()));
    }
}
