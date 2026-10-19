package com.code.pal.agent.validation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded acceptance data, never executable model-written JavaScript or external URLs. */
public record BehaviorCriteria(int schema, List<BrowserAction> browser, List<ApiStep> api, boolean authRequired,
        boolean crudRequired, @JsonInclude(JsonInclude.Include.NON_NULL) Provenance provenance) {
    /** Null is the historical, blocking default; omit it to preserve persisted contract identities. */
    public enum Provenance {
        PLATFORM, MODEL_SPEC
    }

    public BehaviorCriteria(int schema, List<BrowserAction> browser, List<ApiStep> api, boolean authRequired,
            boolean crudRequired) {
        this(schema, browser, api, authRequired, crudRequired, null);
    }

    public boolean modelSpec() {
        return provenance == Provenance.MODEL_SPEC;
    }

    public enum BrowserKind {
        CLICK, FILL, SELECT, ASSERT_TEXT, ASSERT_COUNT, ASSERT_EXISTS, ASSERT_ROUTE, RELOAD
    }

    public record BrowserAction(String id, BrowserKind kind, String selector, String value) {
    }

    public enum ApiKind {
        HEALTH, AUTH_SIGNUP, AUTH_LOGIN, AUTH_REJECT, AUTH_INVALID, CREATE, READ, UPDATE, DELETE, NOT_FOUND, PERSISTENCE
    }

    public record ApiStep(String id, ApiKind kind, String method, String path, String body, int status,
            String tokenVariable, String captureVariable, String capturePointer, String assertPointer,
            String assertValue) {
    }

    public BehaviorCriteria {
        browser = browser == null ? List.of() : List.copyOf(browser);
        api = api == null ? List.of() : List.copyOf(api);
        if (provenance == Provenance.MODEL_SPEC && (!api.isEmpty() || authRequired || crudRequired)) {
            throw new IllegalArgumentException("Model-spec advisory scope is browser-only; API, auth and CRUD checks remain blocking");
        }
        if (schema != 1 || browser.size() > 24 || api.size() > 24 || (browser.isEmpty() && api.isEmpty()))
            throw new IllegalArgumentException("Invalid acceptance schema/size");
        Set<String> ids = new HashSet<>();
        for (var a : browser) {
            id(a.id(), ids);
            Objects.requireNonNull(a.kind());
            bounded(a.selector(), 256);
            bounded(a.value(), 512);
            if (a.kind() != BrowserKind.ASSERT_ROUTE && a.kind() != BrowserKind.RELOAD
                    && (a.selector() == null || a.selector().isBlank()))
                throw new IllegalArgumentException("Acceptance selector missing");
            if (a.kind() != BrowserKind.CLICK && a.kind() != BrowserKind.RELOAD && a.kind() != BrowserKind.ASSERT_EXISTS && a.value() == null)
                throw new IllegalArgumentException("Acceptance value missing");
            if (a.kind() == BrowserKind.ASSERT_COUNT && !a.value().matches("[0-9]{1,4}"))
                throw new IllegalArgumentException("Invalid expected count");
            if (a.kind() == BrowserKind.ASSERT_ROUTE && !a.value().startsWith("/"))
                throw new IllegalArgumentException("Invalid expected route");
        }
        // Browser and API outcomes have separate namespaces (BROWSER_/API_). A feature may use the
        // same label in both; duplicate identities within either sequence remain invalid.
        ids.clear();
        Set<ApiKind> kinds = EnumSet.noneOf(ApiKind.class);
        Set<String> variables = new HashSet<>(Set.of("fixtureId", "fixtureEmail", "fixturePassword", "invalidToken"));
        Set<String> loginTokens = new HashSet<>();
        if (!api.isEmpty() && (api.getFirst().kind() != ApiKind.HEALTH || !"GET".equals(api.getFirst().method())
                || api.getFirst().path().contains("${")))
            throw new IllegalArgumentException("API acceptance must start with a read-only readiness check");
        for (var a : api) {
            id(a.id(), ids);
            Objects.requireNonNull(a.kind());
            kinds.add(a.kind());
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(a.method()))
                throw new IllegalArgumentException("Invalid acceptance HTTP method");
            if (a.path() == null || !a.path().matches("/api/[A-Za-z0-9_./${}-]{1,240}") || a.path().contains("..")
                    || a.path().contains("//"))
                throw new IllegalArgumentException(
                        "Invalid acceptance API path; expand route parameters with captured fixture variables");
            if (a.status() < 100 || a.status() > 599)
                throw new IllegalArgumentException("Invalid acceptance HTTP status");
            boolean success = a.status() >= 200 && a.status() < 300;
            boolean semantics = switch (a.kind()) {
            case HEALTH, READ, PERSISTENCE -> a.method().equals("GET") && success;
            case AUTH_SIGNUP, AUTH_LOGIN, CREATE -> a.method().equals("POST") && success;
            case UPDATE -> Set.of("PUT", "PATCH").contains(a.method()) && success;
            case DELETE -> a.method().equals("DELETE") && success;
            case NOT_FOUND -> a.method().equals("GET") && a.status() == 404;
            case AUTH_REJECT -> a.tokenVariable() == null && Set.of(401, 403).contains(a.status());
            case AUTH_INVALID -> "invalidToken".equals(a.tokenVariable()) && Set.of(401, 403).contains(a.status());
            };
            if (!semantics)
                throw new IllegalArgumentException("Acceptance category cannot weaken required HTTP behavior");
            bounded(a.body(), 4000);
            bounded(a.assertValue(), 512);
            pointer(a.capturePointer());
            pointer(a.assertPointer());
            if (a.tokenVariable() != null && !variables.contains(a.tokenVariable()))
                throw new IllegalArgumentException(
                        "Unknown token variable; use a previously captured name without dollar braces");
            if (authRequired && Set.of(ApiKind.CREATE, ApiKind.READ, ApiKind.UPDATE, ApiKind.DELETE, ApiKind.NOT_FOUND,
                    ApiKind.PERSISTENCE).contains(a.kind()) && !loginTokens.contains(a.tokenVariable()))
                throw new IllegalArgumentException("Account acceptance requires the captured login token");
            for (String template : List.of(a.path(), a.body() == null ? "" : a.body(),
                    a.assertValue() == null ? "" : a.assertValue())) {
                var m = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
                while (m.find())
                    if (!variables.contains(m.group(1)))
                        throw new IllegalArgumentException("Unknown fixture variable");
            }
            if (a.captureVariable() != null) {
                if (!a.captureVariable().matches("[A-Za-z][A-Za-z0-9_]{0,30}") || !variables.add(a.captureVariable())
                        || a.capturePointer() == null)
                    throw new IllegalArgumentException("Invalid captured variable");
            }
            if (authRequired && (a.kind() == ApiKind.AUTH_SIGNUP || a.kind() == ApiKind.AUTH_LOGIN)) {
                if (a.body() == null || !a.body().contains("${fixtureEmail}")
                        || !a.body().contains("${fixturePassword}"))
                    throw new IllegalArgumentException("Account acceptance must use unique fixture credentials");
                if (a.kind() == ApiKind.AUTH_LOGIN) {
                    if (a.captureVariable() == null)
                        throw new IllegalArgumentException("Login token capture is required");
                    loginTokens.add(a.captureVariable());
                }
            }
        }
        if (authRequired && !kinds.containsAll(
                Set.of(ApiKind.AUTH_SIGNUP, ApiKind.AUTH_LOGIN, ApiKind.AUTH_REJECT, ApiKind.AUTH_INVALID)))
            throw new IllegalArgumentException("Authentication acceptance is incomplete");
        if (crudRequired && !kinds.containsAll(Set.of(ApiKind.CREATE, ApiKind.READ, ApiKind.UPDATE, ApiKind.DELETE,
                ApiKind.NOT_FOUND, ApiKind.PERSISTENCE)))
            throw new IllegalArgumentException("CRUD/persistence acceptance is incomplete");
        if (crudRequired && api.stream().anyMatch(
                a -> a.kind() == ApiKind.PERSISTENCE && (a.assertPointer() == null || a.assertValue() == null)))
            throw new IllegalArgumentException("Persistence requires a value assertion");
        if ((authRequired || crudRequired) && api.isEmpty())
            throw new IllegalArgumentException("Required API acceptance missing");
    }

    private static void id(String id, Set<String> ids) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id))
            throw new IllegalArgumentException("Invalid/duplicate acceptance ID");
    }

    private static void bounded(String s, int max) {
        if (s != null && (s.length() > max || s.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Acceptance field too large");
    }

    private static void pointer(String s) {
        bounded(s, 256);
        if (s != null && !s.matches("(?:/[A-Za-z0-9_~-]+)*"))
            throw new IllegalArgumentException("Invalid JSON pointer");
    }
}
