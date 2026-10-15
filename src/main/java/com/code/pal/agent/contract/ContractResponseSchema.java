package com.code.pal.agent.contract;

import com.code.pal.agent.llm.LlmClient;
import tools.jackson.databind.ObjectMapper;
import java.lang.reflect.*;
import java.util.*;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.capability.BuildDecisions;

/** Provider grammar covers shape/types. Backend still verifies all ownership, DAG and behavior rules. */
final class ContractResponseSchema {
    private ContractResponseSchema() {
    }

    private static final Set<String> NULLABLE = Set.of("requestType", "selector", "value", "body", "tokenVariable",
            "captureVariable", "capturePointer", "assertPointer", "assertValue", "content");

    static LlmClient.JsonSchema planner(ContractPlanner.Input input, ObjectMapper mapper) {
        Map<String, Object> schema = type(BuildContract.class);
        var properties = properties(schema);
        for (String key : List.of("specId", "specRevision", "specHash", "planRevision", "replacesContractId", "templateVersion", "batches")) {
            properties.remove(key);
        }
        schema.put("required", List.copyOf(properties.keySet()));
        properties.put("id", Map.of("type", "string", "const", input.id().toString()));
        properties.put("schemaVersion", Map.of("type", "integer", "const", 1));
        properties.put("storage", Map.of("type", "string", "const", input.choice().storage().name()));
        properties.put("auth", Map.of("type", "string", "const", input.choice().auth().name()));
        var limits = type(BuildContract.Limits.class);
        limits.put("const", Map.of("modelCalls", input.limits().modelCalls(), "toolCalls", input.limits().toolCalls(),
                "outputTokens", input.limits().outputTokens(), "timeoutMs", input.limits().timeoutMs()));
        properties.put("limits", limits);
        var theme = new LinkedHashMap<String, Object>();
        var tokens = new LinkedHashMap<String, Object>();
        for (String key : List.of("background", "foreground", "accent", "muted"))
            tokens.put(key, Map.of("type", "string", "pattern",
                    "^#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})$"));
        for (String key : List.of("radius", "spacing"))
            tokens.put(key, Map.of("type", "string", "pattern", "^(?:[0-9]|[12][0-9]|3[0-2])px$"));
        tokens.put("font", Map.of("type", "string", "enum", List.of("system", "serif", "mono")));
        theme.put("type", "object");
        theme.put("properties", tokens);
        theme.put("required", List.copyOf(tokens.keySet()));
        theme.put("additionalProperties", false);
        properties.put("theme", theme);
        if (input.choice().storage() == BuildDecisions.Storage.MERN) {
            properties.put("stack", Map.of("type", "string", "const", "MERN"));
            @SuppressWarnings("unchecked")
            var acceptance = (Map<String, Object>) properties.get("acceptance");
            properties(acceptance).put("crudRequired", Map.of("type", "boolean", "const", true));
            properties(acceptance).put("authRequired",
                    Map.of("type", "boolean", "const", input.choice().auth() == BuildDecisions.Auth.ACCOUNTS));
        }
        return new LlmClient.JsonSchema("build_contract", mapper.writeValueAsString(schema));
    }

    static LlmClient.JsonSchema planner(ContractPlanner.SpecInput input, ObjectMapper mapper) {
        var unit = type(BuildContract.Unit.class);
        properties(unit).remove("acceptanceIds");
        properties(unit).put("objective", Map.of("type", "string", "minLength", 1, "maxLength", 1400));
        properties(unit).put("ownedPaths", Map.of("type", "array", "minItems", 1, "maxItems", 12,
                "items", Map.of("type", "string", "maxLength", 240)));
        unit.put("required", List.of("id", "objective", "ownedPaths", "imports", "dependsOn"));
        var units = Map.of("type", "array", "minItems", 1, "maxItems", 12, "items", unit);
        var schema = Map.of("type", "object", "additionalProperties", false, "required", List.of("units"),
                "properties", Map.of("units", units));
        return new LlmClient.JsonSchema("implementation_units", mapper.writeValueAsString(schema));
    }

    static LlmClient.JsonSchema unit(UnitGenerator.Input input, ObjectMapper mapper) {
        var schema = type(UnitGenerator.Output.class);
        var files = new LinkedHashMap<String, Object>();
        Set<String> assets = new HashSet<>();
        input.contract().assets().forEach(asset -> assets.add(asset.destination()));
        var variants = new ArrayList<Map<String, Object>>();
        for (String path : input.unit().ownedPaths())
            if (!assets.contains(path)) {
                var delta = type(UnitGenerator.Delta.class);
                var fields = properties(delta);
                fields.put("path", Map.of("type", "string", "const", path));
                fields.put("expectedVersion", Map.of("type", "string", "const", input.snapshot().get(path).version()));
                variants.add(delta);
            }
        files.put("type", "array");
        files.put("minItems", variants.size());
        files.put("maxItems", variants.size());
        files.put("items", Map.of("anyOf", variants));
        properties(schema).put("files", files);
        return new LlmClient.JsonSchema("unit_files", mapper.writeValueAsString(schema));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    private static Map<String, Object> type(Type value) {
        var schema = new LinkedHashMap<String, Object>();
        if (value instanceof ParameterizedType generic) {
            if (generic.getRawType() == Map.class) {
                schema.put("type", "object");
                schema.put("additionalProperties", type(generic.getActualTypeArguments()[1]));
                return schema;
            }
            schema.put("type", "array");
            schema.put("items", type(generic.getActualTypeArguments()[0]));
            schema.put("maxItems", 24);
            return schema;
        }
        Class<?> clazz = (Class<?>) value;
        if (clazz == String.class || clazz == UUID.class) {
            schema.put("type", "string");
            schema.put("maxLength", clazz == UUID.class ? 36 : 48_000);
            return schema;
        }
        if (clazz == int.class || clazz == long.class || clazz == Long.class) {
            schema.put("type", "integer");
            return schema;
        }
        if (clazz == boolean.class) {
            schema.put("type", "boolean");
            return schema;
        }
        if (clazz.isEnum()) {
            schema.put("type", "string");
            schema.put("enum", Arrays.stream(clazz.getEnumConstants()).map(Object::toString).toList());
            return schema;
        }
        if (!clazz.isRecord())
            throw new IllegalArgumentException("Unsupported response schema type");
        var fields = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (var component : clazz.getRecordComponents()) {
            var field = type(component.getGenericType());
            String name = component.getName();
            if (component.getType() == String.class) {
                if (Set.of("capturePointer", "assertPointer").contains(name))
                    field.put("pattern", "^(?:/[A-Za-z0-9_~-]+)*$");
                if (name.equals("id") || name.equals("name") || name.equals("owner") || name.equals("routerExport"))
                    field.put("pattern", "^[A-Za-z][A-Za-z0-9_]{0,47}$");
                if (name.equals("requestType") || name.equals("responseType"))
                    field.put("pattern", "^[A-Za-z][A-Za-z0-9_]{0,47}(?:\\[\\])?$");
                if (name.equals("basePath"))
                    field.put("pattern", "^/api(?:/[A-Za-z0-9_-]+)*$");
                if (name.equals("path") && clazz == BuildContract.Api.class)
                    field.put("pattern", "^/api/[A-Za-z0-9_/:-]{1,180}$");
                if (name.equals("path") && clazz == BehaviorCriteria.ApiStep.class)
                    field.put("pattern", "^/api/[A-Za-z0-9_./${}-]{1,240}$");
                if (name.equals("method"))
                    field.put("enum", List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            }
            if (name.equals("status")) {
                field.put("minimum", 100);
                field.put("maximum", 599);
            }
            if (name.equals("units")) {
                field.put("minItems", 1);
                field.put("maxItems", 12);
            }
            if (name.equals("files")) {
                field.put("minItems", 1);
                field.put("maxItems", 12);
            }
            if (NULLABLE.contains(name))
                field = new LinkedHashMap<>(Map.of("anyOf", List.of(field, Map.of("type", "null"))));
            fields.put(name, field);
            required.add(name);
        }
        schema.put("type", "object");
        schema.put("properties", fields);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }
}
