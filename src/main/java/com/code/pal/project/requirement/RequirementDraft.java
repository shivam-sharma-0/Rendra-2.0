package com.code.pal.project.requirement;

import com.code.pal.project.dto.RequirementDtos;
import com.code.pal.project.spec.SpecNormalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Preserve product scope independently of the executable subset of model-drafted checks. */
public final class RequirementDraft {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CHARS = 24_000;

    private RequirementDraft() {}

    public static String capture(JsonNode source) {
        if (source == null || !source.isObject() || !source.path("features").isArray()) return null;
        JsonNode shaped = SpecNormalizer.draft(source);
        ObjectNode draft = (ObjectNode) (shaped == null ? source.deepCopy() : shaped);
        ObjectNode scope = JSON.createObjectNode();
        for (String key : List.of("title", "goal", "pages", "features", "entities", "capabilities")) {
            if (source.has(key)) scope.set(key, source.get(key).deepCopy());
        }
        draft.set("requestedScope", scope);
        draft.put("answersChanged", false);
        String result = JSON.writeValueAsString(draft);
        if (result.length() <= MAX_CHARS) return result;
        // Scope has priority over duplicate executable metadata when both cannot fit.
        String original = JSON.writeValueAsString(source);
        return original.length() <= MAX_CHARS ? original : null;
    }

    public static String withChangedAnswers(String stored, List<String> answers) {
        ObjectNode draft = (ObjectNode) JSON.readTree(stored);
        draft.put("answersChanged", true);
        draft.set("answerOverrides", JSON.valueToTree(answers));
        // Checks assumed recommended answers. Keep all scope, but never run them against changed requirements.
        draft.set("acceptance", JSON.createArrayNode());
        return JSON.writeValueAsString(draft);
    }

    public static RequirementDtos.Review review(String stored, boolean answersChanged) {
        if (stored == null) return null;
        try {
            JsonNode draft = JSON.readTree(stored);
            JsonNode scope = draft.has("requestedScope") ? draft.path("requestedScope") : draft;
            boolean stale = answersChanged || draft.path("answersChanged").asBoolean(false);
            var covered = new ArrayList<String>();
            JsonNode executable = stale ? null : SpecNormalizer.draft(draft);
            if (executable != null) executable.path("acceptance").forEach(check -> covered.add(check.path("featureId").asString("")));
            var features = new ArrayList<RequirementDtos.Feature>();
            for (JsonNode feature : scope.path("features")) {
                if (features.size() >= 24) break;
                String id = text(feature, "id", 80);
                features.add(new RequirementDtos.Feature(id, text(feature, "description", 1400), covered.contains(id)));
            }
            var pages = new ArrayList<String>();
            for (JsonNode page : scope.path("pages")) {
                if (pages.size() >= 20) break;
                pages.add(text(page, "title", 120));
            }
            return new RequirementDtos.Review(text(scope, "goal", 2000), List.copyOf(pages), List.copyOf(features), stale);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonNode node, String key, int limit) {
        String value = node.path(key).asString("").replace('\0', ' ').strip();
        return value.substring(0, Math.min(value.length(), limit));
    }
}
