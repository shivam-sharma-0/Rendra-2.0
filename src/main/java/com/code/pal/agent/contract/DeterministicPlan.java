package com.code.pal.agent.contract;

import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.spec.ApprovedSpec;
import com.code.pal.project.spec.ApprovedSpec.Auth;
import com.code.pal.project.spec.ApprovedSpec.RenderStack;
import com.code.pal.project.spec.ApprovedSpec.Storage;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure single-batch layout for S5. No callers, model, persistence or file writes until coordinator integration. */
public final class DeterministicPlan {
    private static final Set<String> FEATURES = Set.of("CONTENT", "CONTACT_LINK", "NAV_ANCHOR",
            "CALCULATOR", "COUNTER", "LOCAL_CRUD");
    private static final Set<String> STATIC_FEATURES = Set.of("CONTENT", "CONTACT_LINK", "NAV_ANCHOR");
    private static final Set<String> SCALAR_FIELDS = Set.of("STRING", "NUMBER", "BOOLEAN");
    private final ObjectMapper mapper;
    private final BatchBudget budget;

    public DeterministicPlan(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        budget = new BatchBudget(mapper);
    }

    public record Batch(String id, List<String> unitIds, List<String> dependsOn, List<String> ownedPaths,
            List<SharedInterface> interfaces, List<String> acceptanceIds, int inputCap, int outputCap, int estimatedOutputTokens) {
        public Batch {
            unitIds = List.copyOf(unitIds);
            dependsOn = List.copyOf(dependsOn);
            ownedPaths = List.copyOf(ownedPaths);
            interfaces = List.copyOf(interfaces);
            acceptanceIds = List.copyOf(acceptanceIds);
        }
    }

    public record SharedInterface(String path, String hash, List<String> exportedSymbols, List<BuildContract.DataType> types) {
        public SharedInterface {
            exportedSymbols = List.copyOf(exportedSymbols);
            types = List.copyOf(types);
        }
    }

    /** Intermediate layout using existing contract units; not another stored contract or an approval token. */
    public record Layout(UUID specId, long specRevision, String specHash, RenderStack stack, Storage storage, Auth auth,
            String templateVersion, List<BuildContract.Unit> units, List<BuildContract.Route> routes,
            List<BuildContract.DataType> types, Map<String, String> theme, BehaviorCriteria acceptance,
            Batch batch, BatchBudget.Estimate estimate) {
        public Layout {
            units = List.copyOf(units);
            routes = List.copyOf(routes);
            types = List.copyOf(types);
            theme = Map.copyOf(theme);
        }
    }

    public Optional<Layout> plan(ApprovedSpec approved, BatchBudget.Limits limits) {
        Objects.requireNonNull(approved);
        Objects.requireNonNull(limits);
        if (!eligible(approved)) {
            return Optional.empty();
        }
        try {
            return layout(approved, limits);
        } catch (IllegalArgumentException unsupportedMapping) {
            // An unrepresentable feature/action must take the planner path, never lose acceptance.
            return Optional.empty();
        }
    }

    private boolean eligible(ApprovedSpec approved) {
        var spec = approved.spec();
        var desired = approved.desired();
        if (approved.readiness() != ApprovedSpec.Readiness.READY || desired.auth() != Auth.NONE
                || desired.storage() == Storage.SUPABASE || spec.pages().size() != 1
                || !spec.pages().getFirst().path().equals("/") || spec.entities().size() > 1
                || spec.features().size() > 6 || spec.acceptance().size() > 8 || !spec.assets().isEmpty()) {
            return false;
        }
        if (spec.features().stream().anyMatch(feature -> !FEATURES.contains(feature.kind()) || feature.pageIds().size() != 1
                || (feature.kind().equals("LOCAL_CRUD") && feature.entityIds().size() != 1))
                || spec.acceptance().stream().anyMatch(check -> !check.kind().equals("BROWSER"))) {
            return false;
        }
        if (desired.renderStack() == RenderStack.STATIC && (!spec.entities().isEmpty()
                || spec.features().stream().anyMatch(feature -> !STATIC_FEATURES.contains(feature.kind())))) {
            return false;
        }
        if (spec.features().stream().anyMatch(feature -> feature.kind().equals("LOCAL_CRUD"))
                && desired.storage() != Storage.BROWSER) {
            return false;
        }
        return spec.entities().stream().allMatch(entity -> entity.fields().size() <= 8
                && (desired.storage() != Storage.BROWSER || entity.ownership().equals("USER"))
                && entity.fields().stream().allMatch(field -> SCALAR_FIELDS.contains(field.kind())));
    }

    private Optional<Layout> layout(ApprovedSpec approved, BatchBudget.Limits limits) {
        var spec = approved.spec();
        List<BrowserAction> actions = new ArrayList<>();
        for (var scenario : spec.acceptance()) {
            for (int index = 0; index < scenario.steps().size(); index++) {
                var step = scenario.steps().get(index);
                actions.add(new BrowserAction(scenario.id() + "_" + index,
                        BrowserKind.valueOf(step.kind()), step.selector(), step.value()));
            }
        }
        if (actions.size() > 24 || actions.stream().noneMatch(action -> Set.of(BrowserKind.ASSERT_TEXT,
                BrowserKind.ASSERT_COUNT, BrowserKind.ASSERT_ROUTE).contains(action.kind()))) {
            return Optional.empty();
        }
        var criteria = new BehaviorCriteria(1, actions, List.of(), false, false);
        var projections = new ArrayList<BatchBudget.Source>();
        int bytesPerCharacter = sourceByteProjection(mapper.writeValueAsString(spec));
        if (approved.desired().renderStack() == RenderStack.STATIC) {
            projections.add(source("index.html", 3_000, bytesPerCharacter));
            projections.add(source("styles.css", 1_000, bytesPerCharacter));
        } else {
            projections.add(source("src/App.tsx", 4_000, bytesPerCharacter));
            projections.add(source("src/index.css", 1_000, bytesPerCharacter));
            if (approved.desired().storage() == Storage.BROWSER) {
                projections.add(source("src/local-state.ts", 1_000, bytesPerCharacter));
            }
        }
        List<String> paths = projections.stream().map(BatchBudget.Source::path).toList();
        List<String> acceptanceIds = actions.stream().map(BrowserAction::id).toList();
        var unit = new BuildContract.Unit("app", "Implement all approved single-page features",
                paths, List.of(), List.of(), acceptanceIds);
        String component = approved.desired().renderStack() == RenderStack.STATIC ? "index.html" : "src/App.tsx";
        var route = new BuildContract.Route("/", component, unit.id());
        var types = spec.entities().stream().map(entity -> new BuildContract.DataType(entity.id(),
                entity.fields().stream().map(field -> new BuildContract.Field(field.name(),
                        BuildContract.Kind.valueOf(field.kind()), !field.required())).toList())).toList();
        var theme = spec.theme();
        Map<String, String> tokens = Map.of("background", theme.background(), "foreground", theme.foreground(),
                "accent", theme.accent(), "muted", theme.muted(), "radius", theme.radius(), "spacing", theme.spacing(), "font", theme.font());
        // This is a provisional wire projection. Actual policy/schema/transport admission is checked again at integration.
        String input = mapper.writeValueAsString(Map.of("approvedSpec", approved, "templateVersion", Starter.TEMPLATE_VERSION,
                "unit", unit, "route", route, "types", types, "theme", tokens, "acceptance", criteria));
        var estimate = budget.estimate(projections, input, limits);
        if (!estimate.fits()) {
            return Optional.empty();
        }
        var batch = new Batch("app", List.of(unit.id()), List.of(), paths, List.of(), acceptanceIds,
                limits.inputCap(), estimate.outputCap(), estimate.visibleTokens() + estimate.reasoningTokens());
        return Optional.of(new Layout(approved.specId(), approved.specRevision(), approved.specHash(),
                approved.desired().renderStack(), approved.desired().storage(), approved.desired().auth(),
                Starter.TEMPLATE_VERSION, List.of(unit), List.of(route), types, tokens, criteria, batch, estimate));
    }

    private static BatchBudget.Source source(String path, int characters, int bytesPerCharacter) {
        return new BatchBudget.Source(path, "absent", characters, (long) characters * bytesPerCharacter);
    }

    private static int sourceByteProjection(String data) {
        return data.codePoints().map(codePoint -> {
            String value = Character.toString(codePoint);
            return (int) Math.ceil((double) value.getBytes(StandardCharsets.UTF_8).length / value.length());
        }).max().orElse(1);
    }
}
