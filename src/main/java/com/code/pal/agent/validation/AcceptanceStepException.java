package com.code.pal.agent.validation;

import java.util.List;

/** Structured validator feedback; never includes a rejected selector, value or provider message. */
public final class AcceptanceStepException extends IllegalArgumentException {
    private final boolean routeValue;
    private final String prefix;
    private final String allowed;

    public AcceptanceStepException(String sequence, int index, String id, Enum<?> kind, String field,
            Object value, String allowed, RuntimeException cause) {
        super(format(sequence, index, id, kind, field, value) + "; " + allowed, cause);
        this.prefix = format(sequence, index, id, kind, field, value);
        this.allowed = allowed;
        this.routeValue = kind == BehaviorCriteria.BrowserKind.ASSERT_ROUTE && field.equals("value");
    }

    public String feedback(List<String> routes) {
        return prefix + "; " + (routeValue && !routes.isEmpty()
                ? "must match one of the contract routes: " + String.join(", ", routes) : allowed);
    }

    private static String format(String sequence, int index, String id, Enum<?> kind, String field, Object value) {
        String safeId = id != null && id.matches("[A-Za-z0-9_-]{1,64}") ? id : "INVALID_ID";
        String state = value == null ? "is null" : value instanceof String text && text.isBlank() ? "is empty" : "is invalid";
        return sequence + " " + safeId + " step " + index + " " + (kind == null ? "UNKNOWN_KIND" : kind.name())
                + ": " + field + " " + state;
    }
}
