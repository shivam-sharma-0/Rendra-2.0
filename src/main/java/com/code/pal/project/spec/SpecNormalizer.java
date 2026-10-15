package com.code.pal.project.spec;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a model-drafted spec into a valid {@link ApprovedSpec.Spec} without a second model call: identifiers are
 * made safe and consistent, page/feature links agree, unknown references and invalid steps are dropped, unsafe theme
 * values fall back to defaults and every feature gets at least a route check. Nothing here grants capabilities;
 * persistence comes from the server's storage decision.
 */
public final class SpecNormalizer {
    private static final Set<String> STEP_KINDS = Set.of("CLICK", "FILL", "SELECT", "ASSERT_TEXT", "ASSERT_COUNT",
            "ASSERT_ROUTE", "RELOAD");
    private static final Set<String> FONTS = Set.of("system", "serif", "mono");
    private static final String COLOR = "#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})";
    private static final String SIZE = "(?:[0-9]|[12][0-9]|3[0-2])px";

    private SpecNormalizer() {
    }

    public static ApprovedSpec.Spec normalize(JsonNode node, ApprovedSpec.Storage storage) {
        String title = text(node.path("title").asString(""), 120, "App");
        String goal = text(node.path("goal").asString(""), 2_000, title);
        String language = text(node.path("language").asString(""), 32, "en");

        var pages = new LinkedHashMap<String, MutablePage>();
        var paths = new HashSet<String>();
        for (JsonNode page : limit(node.path("pages"), 20)) {
            String id = id(page.path("id").asString(""));
            if (id == null || pages.containsKey(id)) {
                continue;
            }
            String path = page.path("path").asString("");
            if (!path.matches("/[A-Za-z0-9_/:.-]{0,120}") || path.contains("..") || path.contains("//") || paths.contains(path)) {
                path = pages.isEmpty() ? "/" : "/" + id.toLowerCase(Locale.ROOT);
            }
            if (!paths.add(path)) {
                continue;
            }
            pages.put(id, new MutablePage(id, path, text(page.path("title").asString(""), 120, id), ids(page.path("featureIds"))));
        }
        if (pages.isEmpty()) {
            pages.put("home", new MutablePage("home", "/", title, new LinkedHashSet<>()));
        }

        var entities = new LinkedHashMap<String, ApprovedSpec.Entity>();
        for (JsonNode entity : limit(node.path("entities"), 12)) {
            String id = id(entity.path("id").asString(""));
            if (id == null || entities.containsKey(id)) {
                continue;
            }
            var fields = new ArrayList<ApprovedSpec.Field>();
            var names = new HashSet<String>();
            for (JsonNode field : limit(entity.path("fields"), 24)) {
                String name = id(field.path("name").asString(""));
                String kind = id(field.path("kind").asString("STRING").toUpperCase(Locale.ROOT));
                if (name != null && names.add(name)) {
                    fields.add(new ApprovedSpec.Field(name, kind == null ? "STRING" : kind, field.path("required").asBoolean(false)));
                }
            }
            if (!fields.isEmpty()) {
                entities.put(id, new ApprovedSpec.Entity(id, fields, storage,
                        text(entity.path("ownership").asString(""), 48, "USER")));
            }
        }

        var features = new LinkedHashMap<String, MutableFeature>();
        for (JsonNode feature : limit(node.path("features"), 24)) {
            String id = id(feature.path("id").asString(""));
            if (id == null || features.containsKey(id)) {
                continue;
            }
            String kind = id(feature.path("kind").asString("CONTENT").toUpperCase(Locale.ROOT));
            var pageIds = ids(feature.path("pageIds"));
            pageIds.retainAll(pages.keySet());
            var entityIds = ids(feature.path("entityIds"));
            entityIds.retainAll(entities.keySet());
            features.put(id, new MutableFeature(id, kind == null ? "CONTENT" : kind,
                    text(feature.path("description").asString(""), 1_400, id), pageIds, entityIds));
        }
        if (features.isEmpty()) {
            features.put("content", new MutableFeature("content", "CONTENT", goal.length() > 1_400 ? title : goal,
                    new LinkedHashSet<>(), new LinkedHashSet<>()));
        }
        // Links agree in both directions; a feature without a page lives on the first page.
        for (var page : pages.values()) {
            page.featureIds().retainAll(features.keySet());
            page.featureIds().forEach(feature -> features.get(feature).pageIds().add(page.id()));
        }
        String first = pages.keySet().iterator().next();
        for (var feature : features.values()) {
            if (feature.pageIds().isEmpty()) {
                feature.pageIds().add(first);
            }
            feature.pageIds().forEach(page -> pages.get(page).featureIds().add(feature.id()));
        }

        var acceptance = new ArrayList<ApprovedSpec.Acceptance>();
        var scenarioIds = new HashSet<String>();
        for (JsonNode scenario : limit(node.path("acceptance"), 24)) {
            String id = id(scenario.path("id").asString(""));
            String feature = id(scenario.path("featureId").asString(""));
            if (id == null || feature == null || !features.containsKey(feature) || scenarioIds.contains(id)) {
                continue;
            }
            var steps = new ArrayList<ApprovedSpec.Step>();
            for (JsonNode step : limit(scenario.path("steps"), 24)) {
                var valid = step(step);
                if (valid != null) {
                    steps.add(valid);
                }
            }
            if (!steps.isEmpty()) {
                scenarioIds.add(id);
                acceptance.add(new ApprovedSpec.Acceptance(id, feature, "BROWSER", steps,
                        text(scenario.path("expected").asString(""), 512, features.get(feature).description())));
            }
        }
        var covered = new HashSet<String>();
        acceptance.forEach(scenario -> covered.add(scenario.featureId()));
        for (var feature : features.values()) {
            String id = feature.id().length() > 40 ? feature.id().substring(0, 40) + "_ok" : feature.id() + "_ok";
            if (!covered.contains(feature.id()) && acceptance.size() < 24 && scenarioIds.add(id)) {
                String path = pages.get(feature.pageIds().iterator().next()).path();
                acceptance.add(new ApprovedSpec.Acceptance(id, feature.id(), "BROWSER",
                        List.of(new ApprovedSpec.Step("ASSERT_ROUTE", null, path)), "The page for " + feature.id() + " loads"));
            }
        }

        JsonNode theme = node.path("theme");
        var exclusions = new ArrayList<String>();
        for (JsonNode exclusion : limit(node.path("constraints").path("exclusions"), 16)) {
            String value = exclusion.asString("").strip();
            if (!value.isEmpty() && value.length() <= 256) {
                exclusions.add(value);
            }
        }
        return new ApprovedSpec.Spec(title, language, goal,
                pages.values().stream().map(page -> new ApprovedSpec.Page(page.id(), page.path(), page.title(),
                        List.copyOf(page.featureIds()))).toList(),
                List.copyOf(entities.values()),
                features.values().stream().map(feature -> new ApprovedSpec.Feature(feature.id(), feature.kind(),
                        feature.description(), List.copyOf(feature.pageIds()), List.copyOf(feature.entityIds()))).toList(),
                new ApprovedSpec.Theme(color(theme, "background", "#ffffff"), color(theme, "foreground", "#111827"),
                        color(theme, "accent", "#4f46e5"), color(theme, "muted", "#6b7280"), size(theme, "radius"),
                        size(theme, "spacing"), FONTS.contains(theme.path("font").asString("")) ? theme.path("font").asString("")
                                : "system"),
                acceptance, List.of(), new ApprovedSpec.Constraints(true, true, exclusions));
    }

    private static ApprovedSpec.Step step(JsonNode step) {
        String kind = step.path("kind").asString("").toUpperCase(Locale.ROOT);
        String selector = step.path("selector").isString() ? step.path("selector").asString().strip() : null;
        String value = step.path("value").isNull() || step.path("value").isMissingNode() ? null : step.path("value").asString("");
        if (!STEP_KINDS.contains(kind) || selector != null && selector.length() > 256 || value != null && value.length() > 512) {
            return null;
        }
        boolean needsSelector = !kind.equals("ASSERT_ROUTE") && !kind.equals("RELOAD");
        boolean needsValue = !kind.equals("CLICK") && !kind.equals("RELOAD");
        if (needsSelector && (selector == null || selector.isEmpty()) || needsValue && value == null
                || kind.equals("ASSERT_COUNT") && !value.matches("[0-9]{1,4}") || kind.equals("ASSERT_ROUTE") && !value.startsWith("/")) {
            return null;
        }
        return new ApprovedSpec.Step(kind, needsSelector ? selector : null, value);
    }

    private static String color(JsonNode theme, String key, String fallback) {
        String value = theme.path(key).asString("");
        return value.matches(COLOR) ? value : fallback;
    }

    private static String size(JsonNode theme, String key) {
        String value = theme.path(key).asString("");
        return value.matches(SIZE) ? value : "8px";
    }

    /** A safe identifier (letter first, letters/digits/_ only, at most 48), or null when nothing usable remains. */
    static String id(String raw) {
        String value = raw == null ? "" : raw.strip().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (value.isEmpty()) {
            return null;
        }
        if (!Character.isLetter(value.charAt(0))) {
            value = "x" + value;
        }
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    private static LinkedHashSet<String> ids(JsonNode values) {
        var result = new LinkedHashSet<String>();
        for (JsonNode value : limit(values, 24)) {
            String id = id(value.asString(""));
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private static String text(String value, int max, String fallback) {
        String clean = value == null ? "" : value.replace('\0', ' ').strip();
        if (clean.isEmpty()) {
            clean = fallback;
        }
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static List<JsonNode> limit(JsonNode array, int max) {
        var result = new ArrayList<JsonNode>();
        if (array != null && array.isArray()) {
            for (JsonNode value : array) {
                if (result.size() >= max) {
                    break;
                }
                if (value != null && !value.isNull()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private record MutablePage(String id, String path, String title, LinkedHashSet<String> featureIds) {
    }

    private record MutableFeature(String id, String kind, String description, LinkedHashSet<String> pageIds,
            LinkedHashSet<String> entityIds) {
    }
}
