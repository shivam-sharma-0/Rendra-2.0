package com.code.pal.project.spec;

import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Frozen S4-to-S5 data contract. Persisted approval/capability authority must still be checked by its consumer. */
public record ApprovedSpec(int schemaVersion, UUID specId, long specRevision, String specHash,
        UUID projectId, UUID requirementsProjection, long requirementsRevision, Status status,
        String capabilityRevision, Desired desired, Readiness readiness, Spec spec) {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BYTES = 32_000;

    public enum Status { APPROVED }
    public enum RenderStack { STATIC, VITE }
    public enum Storage { NONE, BROWSER, SUPABASE }
    public enum Auth { NONE, ACCOUNTS }
    public enum Readiness { READY, CONNECTION_REQUIRED, UNSUPPORTED }

    public ApprovedSpec {
        if (schemaVersion != 1 || specRevision < 1 || requirementsRevision < 0) {
            throw new IllegalArgumentException("Unsupported spec version or revision");
        }
        Objects.requireNonNull(specId);
        Objects.requireNonNull(projectId);
        Objects.requireNonNull(requirementsProjection);
        Objects.requireNonNull(status);
        Objects.requireNonNull(desired);
        Objects.requireNonNull(readiness);
        Objects.requireNonNull(spec);
        fingerprint(specHash);
        fingerprint(capabilityRevision);
        if (readiness == Readiness.CONNECTION_REQUIRED && desired.storage() != Storage.SUPABASE) {
            throw new IllegalArgumentException("Only a Supabase choice can need a connection");
        }
        for (Entity entity : spec.entities()) {
            if (entity.persistence() != desired.storage()) {
                throw new IllegalArgumentException("Entity persistence differs from the desired choice");
            }
        }
        var envelope = Map.ofEntries(Map.entry("schemaVersion", schemaVersion), Map.entry("specId", specId),
                Map.entry("specRevision", specRevision), Map.entry("specHash", specHash), Map.entry("projectId", projectId),
                Map.entry("requirementsProjection", requirementsProjection), Map.entry("requirementsRevision", requirementsRevision),
                Map.entry("status", status), Map.entry("capabilityRevision", capabilityRevision), Map.entry("desired", desired),
                Map.entry("readiness", readiness), Map.entry("spec", spec));
        if (JSON.writeValueAsBytes(envelope).length > MAX_BYTES) {
            throw new IllegalArgumentException("Approved spec exceeds its UTF-8 capacity");
        }
    }

    public record Desired(RenderStack renderStack, Storage storage, Auth auth) {
        public Desired {
            Objects.requireNonNull(renderStack);
            Objects.requireNonNull(storage);
            Objects.requireNonNull(auth);
            if ((renderStack == RenderStack.STATIC && storage != Storage.NONE)
                    || (auth == Auth.ACCOUNTS && storage != Storage.SUPABASE)) {
                throw new IllegalArgumentException("Incompatible rendering, storage or account choice");
            }
        }
    }

    public record Spec(String title, String language, String goal, List<Page> pages, List<Entity> entities,
            List<Feature> features, Theme theme, List<Acceptance> acceptance, List<Asset> assets, Constraints constraints) {
        public Spec {
            text(title, 120);
            text(language, 32);
            text(goal, 2_000);
            pages = bounded(pages, 20);
            entities = bounded(entities, 12);
            features = bounded(features, 24);
            acceptance = bounded(acceptance, 24);
            assets = bounded(assets, 16);
            Objects.requireNonNull(theme);
            Objects.requireNonNull(constraints);
            if (pages.isEmpty() || features.isEmpty() || acceptance.isEmpty()) {
                throw new IllegalArgumentException("Spec needs pages, features and acceptance");
            }
            Set<String> pageIds = unique(pages.stream().map(Page::id).toList());
            Set<String> entityIds = unique(entities.stream().map(Entity::id).toList());
            Set<String> featureIds = unique(features.stream().map(Feature::id).toList());
            unique(pages.stream().map(Page::path).toList());
            unique(acceptance.stream().map(Acceptance::id).toList());
            unique(assets.stream().map(Asset::projectPath).toList());
            for (Page page : pages) {
                requireReferences(featureIds, page.featureIds());
            }
            for (Feature feature : features) {
                requireReferences(pageIds, feature.pageIds());
                requireReferences(entityIds, feature.entityIds());
                for (Page page : pages) {
                    if (page.featureIds().contains(feature.id()) != feature.pageIds().contains(page.id())) {
                        throw new IllegalArgumentException("Page and feature references disagree");
                    }
                }
            }
            for (Acceptance scenario : acceptance) {
                if (!featureIds.contains(scenario.featureId())) {
                    throw new IllegalArgumentException("Unknown acceptance feature");
                }
            }
            var covered = new HashSet<>(acceptance.stream().map(Acceptance::featureId).toList());
            if (!covered.containsAll(featureIds)) {
                throw new IllegalArgumentException("Feature has no acceptance scenario");
            }
        }
    }

    public record Page(String id, String path, String title, List<String> featureIds) {
        public Page {
            identifier(id);
            text(title, 120);
            if (path == null || !path.matches("/[A-Za-z0-9_/:.-]{0,120}") || path.contains("..") || path.contains("//")) {
                throw new IllegalArgumentException("Invalid page route");
            }
            featureIds = identifiers(featureIds, 24);
        }
    }

    public record Entity(String id, List<Field> fields, Storage persistence, String ownership) {
        public Entity {
            identifier(id);
            fields = bounded(fields, 24);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Entity needs fields");
            }
            unique(fields.stream().map(Field::name).toList());
            Objects.requireNonNull(persistence);
            text(ownership, 48);
        }
    }

    public record Field(String name, String kind, boolean required) {
        public Field {
            identifier(name);
            identifier(kind);
        }
    }

    public record Feature(String id, String kind, String description, List<String> pageIds, List<String> entityIds) {
        public Feature {
            identifier(id);
            identifier(kind);
            text(description, 1_400);
            pageIds = identifiers(pageIds, 20);
            entityIds = identifiers(entityIds, 12);
        }
    }

    public record Theme(String background, String foreground, String accent, String muted,
            String radius, String spacing, String font) {
        public Theme {
            for (String color : List.of(background, foreground, accent, muted)) {
                if (!color.matches("#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})")) {
                    throw new IllegalArgumentException("Unsafe theme color");
                }
            }
            for (String size : List.of(radius, spacing)) {
                if (!size.matches("(?:[0-9]|[12][0-9]|3[0-2])px")) {
                    throw new IllegalArgumentException("Unsafe theme size");
                }
            }
            if (!Set.of("system", "serif", "mono").contains(font)) {
                throw new IllegalArgumentException("Unsafe theme font");
            }
        }
    }

    public record Acceptance(String id, String featureId, String kind, List<Step> steps, String expected) {
        public Acceptance {
            identifier(id);
            identifier(featureId);
            identifier(kind);
            steps = bounded(steps, 24);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("Acceptance needs explicit steps");
            }
            text(expected, 512);
        }
    }

    /** Kinds are extensible data: unknown kinds must not be executed or dropped by a consumer. */
    public record Step(String kind, String selector, String value) {
        public Step {
            identifier(kind);
            optionalText(selector, 256);
            optionalText(value, 512);
        }
    }

    public record Asset(String projectPath, String fingerprint, String purpose) {
        public Asset {
            text(projectPath, 240);
            ApprovedSpec.fingerprint(fingerprint);
            text(purpose, 256);
        }
    }

    public record Constraints(boolean responsive, boolean accessibility, List<String> exclusions) {
        public Constraints {
            exclusions = bounded(exclusions, 16);
            exclusions.forEach(value -> text(value, 256));
        }
    }

    private static <T> List<T> bounded(List<T> values, int max) {
        if (values == null || values.size() > max || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Spec collection is missing or oversized");
        }
        return List.copyOf(values);
    }

    private static List<String> identifiers(List<String> values, int max) {
        List<String> result = bounded(values, max);
        result.forEach(ApprovedSpec::identifier);
        unique(result);
        return result;
    }

    private static Set<String> unique(List<String> values) {
        var unique = new HashSet<>(values);
        if (unique.size() != values.size()) {
            throw new IllegalArgumentException("Duplicate spec identity");
        }
        return unique;
    }

    private static void requireReferences(Set<String> known, List<String> references) {
        if (!known.containsAll(references)) {
            throw new IllegalArgumentException("Unknown spec reference");
        }
    }

    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Invalid spec identifier");
        }
    }

    private static void fingerprint(String value) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid spec fingerprint");
        }
    }

    private static void optionalText(String value, int max) {
        if (value != null && (value.length() > max || value.indexOf('\0') >= 0)) {
            throw new IllegalArgumentException("Invalid spec text");
        }
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing spec text");
        }
        optionalText(value, max);
    }
}
