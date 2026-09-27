package com.code.pal.agent.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Minimal JSON-schema validator for the object schemas exposed by agent tools. */
final class ToolInputValidator {
    private ToolInputValidator() {
    }

    static List<ToolValidationError> validate(Map<String, Object> schema, Map<String, Object> args) {
        List<ToolValidationError> errors = new ArrayList<>();
        if (args == null) {
            return List.of(new ToolValidationError("$", "REQUIRED", "Arguments object is required"));
        }

        for (Object required : list(schema.get("required"))) {
            String field = required.toString();
            if (!args.containsKey(field) || args.get(field) == null) {
                errors.add(new ToolValidationError(field, "REQUIRED", "Required field is missing"));
            }
        }

        Map<String, Object> properties = map(schema.get("properties"));
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Map<String, Object> property = map(properties.get(entry.getKey()));
            if (property.isEmpty()) {
                errors.add(new ToolValidationError(entry.getKey(), "UNKNOWN_FIELD", "Field is not defined by the tool schema"));
                continue;
            }
            Object value = entry.getValue();
            if (value == null) continue;
            String type = String.valueOf(property.get("type"));
            if (!matches(type, value)) {
                errors.add(new ToolValidationError(entry.getKey(), "INVALID_TYPE", "Expected " + type));
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                errors.add(new ToolValidationError(entry.getKey(), "BLANK", "Value must not be blank"));
            }
            if (value instanceof String text && property.get("maxLength") instanceof Number maximum
                    && text.length() > maximum.intValue()) {
                errors.add(new ToolValidationError(entry.getKey(), "TOO_LONG", "Value exceeds the maximum length"));
            }
            if (value instanceof Number number) {
                long numeric = number.longValue();
                if (property.get("minimum") instanceof Number minimum && numeric < minimum.longValue()) {
                    errors.add(new ToolValidationError(entry.getKey(), "BELOW_MINIMUM", "Value is below the minimum"));
                }
                if (property.get("maximum") instanceof Number maximum && numeric > maximum.longValue()) {
                    errors.add(new ToolValidationError(entry.getKey(), "ABOVE_MAXIMUM", "Value exceeds the maximum"));
                }
            }
        }
        return List.copyOf(errors);
    }

    private static boolean matches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }
}
