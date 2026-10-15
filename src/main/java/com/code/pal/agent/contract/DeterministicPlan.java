package com.code.pal.agent.contract;

import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.validation.BehaviorCriteria;
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

/** Pure single-batch layout for the approved-spec coordinator path; no model, persistence or file writes. */
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
        boolean staticSite = desired.renderStack() == RenderStack.STATIC;
        if (approved.readiness() != ApprovedSpec.Readiness.READY || desired.auth() != Auth.NONE
                || desired.storage() == Storage.SUPABASE || (staticSite ? spec.pages().size() > 5 : spec.pages().size() != 1)
                || spec.pages().stream().noneMatch(page -> page.path().equals("/")) || spec.entities().size() > 1
                || spec.features().size() > 6 || spec.acceptance().size() > 8 || !spec.assets().isEmpty()) {
            return false;
        }
        if (spec.features().stream().anyMatch(feature -> (!FEATURES.contains(feature.kind()) && !customContent(approved, feature))
                || feature.pageIds().isEmpty() || (!staticSite && feature.pageIds().size() != 1)
                || (feature.kind().equals("LOCAL_CRUD") && feature.entityIds().size() != 1))
                || spec.acceptance().stream().anyMatch(check -> !check.kind().equals("BROWSER"))) {
            return false;
        }
        if (desired.renderStack() == RenderStack.STATIC && (!spec.entities().isEmpty()
                || spec.features().stream().anyMatch(feature -> !STATIC_FEATURES.contains(feature.kind()) && !customContent(approved, feature)))) {
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
        var criteria = SpecPlanValues.acceptance(approved);
        var actions = criteria.browser();
        if (actions.stream().noneMatch(action -> Set.of(BrowserKind.ASSERT_TEXT,
                BrowserKind.ASSERT_COUNT, BrowserKind.ASSERT_ROUTE).contains(action.kind()))) {
            return Optional.empty();
        }
        var pageFiles = SpecPlanValues.pages(approved);
        var projections = new ArrayList<BatchBudget.Source>();
        int bytesPerCharacter = sourceByteProjection(mapper.writeValueAsString(spec));
        if (approved.desired().renderStack() == RenderStack.STATIC) {
            for (var page : pageFiles) {
                projections.add(source(page.path(), pageFiles.size() == 1 ? 3_000 : 2_500, bytesPerCharacter));
            }
            projections.add(source("styles.css", 1_000, bytesPerCharacter));
        } else {
            projections.add(source("src/App.tsx", 4_000, bytesPerCharacter));
            projections.add(source("src/index.css", 1_000, bytesPerCharacter));
            if (approved.desired().storage() == Storage.BROWSER) {
                projections.add(source("src/local-state.ts", 1_000, bytesPerCharacter));
            }
        }
        List<String> paths = projections.stream().map(BatchBudget.Source::path).toList();
        List<String> acceptanceIds = actions.stream().map(action -> action.id()).toList();
        String objective = pageFiles.size() == 1 ? "Implement all approved single-page features" : "Implement all approved content pages and navigation";
        var unit = new BuildContract.Unit("app", objective,
                paths, List.of(), List.of(), acceptanceIds);
        var routes = pageFiles.stream().map(page -> new BuildContract.Route(page.route(), page.path(), unit.id())).toList();
        var types = SpecPlanValues.types(approved);
        var tokens = SpecPlanValues.theme(approved);
        // This is a provisional wire projection. Actual policy/schema/transport admission is checked again at integration.
        String input = mapper.writeValueAsString(Map.of("approvedSpec", approved, "templateVersion", Starter.TEMPLATE_VERSION,
                "unit", unit, "routes", routes, "types", types, "theme", tokens, "acceptance", criteria));
        var estimate = budget.estimate(projections, input, limits);
        if (!estimate.fits()) {
            return Optional.empty();
        }
        var batch = new Batch("app", List.of(unit.id()), List.of(), paths, List.of(), acceptanceIds,
                limits.inputCap(), estimate.outputCap(), estimate.visibleTokens() + estimate.reasoningTokens());
        return Optional.of(new Layout(approved.specId(), approved.specRevision(), approved.specHash(),
                approved.desired().renderStack(), approved.desired().storage(), approved.desired().auth(),
                Starter.TEMPLATE_VERSION, List.of(unit), routes, types, tokens, criteria, batch, estimate));
    }

    private static boolean customContent(ApprovedSpec approved, ApprovedSpec.Feature feature) {
        return approved.desired().renderStack() == RenderStack.STATIC && feature.kind().equals("CUSTOM")
                && feature.entityIds().isEmpty() && approved.spec().acceptance().stream()
                        .filter(scenario -> scenario.featureId().equals(feature.id())).flatMap(scenario -> scenario.steps().stream())
                        .allMatch(step -> Set.of("ASSERT_TEXT", "ASSERT_COUNT", "ASSERT_ROUTE", "RELOAD").contains(step.kind()));
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
