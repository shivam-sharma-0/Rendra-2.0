package com.code.pal.agent.validation;

import com.code.pal.project.spec.ApprovedSpec;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Complete approved data scope; never derives coverage from the pruned browser-scenario subset. */
final class SupabaseValidationScope {
    private static final Set<String> KINDS = Set.of("STRING", "NUMBER", "BOOLEAN", "DATE");

    private SupabaseValidationScope() {}

    static List<ApprovedSpec.Entity> entities(JsonNode draft, boolean accounts) {
        if (draft == null || !draft.isObject() || draft.path("answersChanged").asBoolean(false)) {
            throw new IllegalArgumentException("Current approved data scope is missing or needs refreshed checks after changed answers");
        }
        JsonNode scope = draft.has("requestedScope") ? draft.path("requestedScope") : draft;
        JsonNode source = scope.path("entities");
        if (!source.isArray() || source.size() > 12) {
            throw new IllegalArgumentException("A complete supported approved entity list is required for cloud data acceptance");
        }
        var entities = new ArrayList<ApprovedSpec.Entity>();
        var ids = new HashSet<String>();
        for (JsonNode entity : source) {
            String id = identifier(entity.path("id"));
            String ownership = entity.path("ownership").asString("").toUpperCase(Locale.ROOT);
            if (!ids.add(id) || !Set.of("USER", "SHARED").contains(ownership) || !accounts && ownership.equals("USER")) {
                throw new IllegalArgumentException("Approved entities need unique identities and a supported ownership/account choice");
            }
            JsonNode sourceFields = entity.path("fields");
            if (!sourceFields.isArray() || sourceFields.isEmpty() || sourceFields.size() > 24) {
                throw new IllegalArgumentException("Every approved entity needs its complete supported field list");
            }
            var fields = new ArrayList<ApprovedSpec.Field>();
            var names = new HashSet<String>();
            for (JsonNode field : sourceFields) {
                String name = identifier(field.path("name"));
                String kind = field.path("kind").asString("").toUpperCase(Locale.ROOT);
                if (!names.add(name) || !KINDS.contains(kind) || !field.path("required").isBoolean()) {
                    throw new IllegalArgumentException("Approved data fields contain unsupported or ambiguous metadata");
                }
                fields.add(new ApprovedSpec.Field(name, kind, field.path("required").asBoolean()));
            }
            entities.add(new ApprovedSpec.Entity(id, fields, ApprovedSpec.Storage.SUPABASE, ownership));
        }
        return List.copyOf(entities);
    }

    private static String identifier(JsonNode node) {
        if (!node.isString() || !node.asString().matches("[A-Za-z][A-Za-z0-9_]{0,47}")) {
            throw new IllegalArgumentException("Approved data identities need an explicit supported schema mapping");
        }
        return node.asString();
    }
}
