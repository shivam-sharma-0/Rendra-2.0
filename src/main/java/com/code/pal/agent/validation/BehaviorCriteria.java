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
        /** The canonical failure diagnostic: names the step's platform-validated kind, selector and value so a repair
         * knows which element to render, and stays exactly reproducible from the contract for owner attribution. */
        public String failureMessage() {
            // Capped well below the 500-character runtime diagnostic bound, so the text is never truncated.
            return "Required interaction " + id + " did not pass: " + kind
                    + (selector == null ? "" : " " + abbreviate(selector, 200))
                    + (value == null ? "" : " expecting \"" + abbreviate(value, 80) + "\"")
                    + "; render a matching element on the page where this step runs";
        }

        private static String abbreviate(String text, int max) {
            return text.length() <= max ? text : text.substring(0, max) + "...";
        }
    }

    public enum ApiKind {
        HEALTH, AUTH_SIGNUP, AUTH_LOGIN, AUTH_REJECT, AUTH_INVALID, CREATE, READ, UPDATE, DELETE, NOT_FOUND, PERSISTENCE
    }

    public record ApiStep(String id, ApiKind kind, String method, String path, String body, int status,
            String tokenVariable, String captureVariable, String capturePointer, String assertPointer,
            String assertValue) {
    }

    public static final int BROWSER_CAP = 24;
    public static final int API_CAP = 48;

    public BehaviorCriteria {
        browser = browser == null ? List.of() : List.copyOf(browser);
        api = api == null ? List.of() : List.copyOf(api);
        if (provenance == Provenance.MODEL_SPEC && (!api.isEmpty() || authRequired || crudRequired)) {
            throw new IllegalArgumentException("Model-spec advisory scope is browser-only; API, auth and CRUD checks remain blocking");
        }
        if (schema != 1 || browser.size() > BROWSER_CAP || api.size() > API_CAP || (browser.isEmpty() && api.isEmpty()))
            throw new IllegalArgumentException("Invalid acceptance schema/size");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < browser.size(); index++) {
            var a = browser.get(index);
            String field = "id", allowed = "must be a unique 1-64 character acceptance ID";
            Object value = a.id();
            try {
                id(a.id(), ids);
                field = "kind"; value = a.kind(); allowed = "must be one of " + Arrays.toString(BrowserKind.values());
                Objects.requireNonNull(a.kind());
                field = "selector"; value = a.selector(); allowed = "must be a non-empty selector of at most 256 characters";
                bounded(a.selector(), 256);
                if (a.kind() != BrowserKind.ASSERT_ROUTE && a.kind() != BrowserKind.RELOAD
                        && (a.selector() == null || a.selector().isBlank()))
                    throw new IllegalArgumentException("Acceptance selector missing");
                field = "value"; value = a.value();
                allowed = switch (a.kind()) {
                case ASSERT_ROUTE -> "must be a non-empty local route path beginning /";
                case ASSERT_COUNT -> "must be a decimal count from 0 to 9999";
                default -> "must be a string of at most 512 characters; null only for CLICK, RELOAD or ASSERT_EXISTS";
                };
                bounded(a.value(), 512);
                if (a.kind() != BrowserKind.CLICK && a.kind() != BrowserKind.RELOAD && a.kind() != BrowserKind.ASSERT_EXISTS && a.value() == null)
                    throw new IllegalArgumentException("Acceptance value missing");
                if (a.kind() == BrowserKind.ASSERT_COUNT && !a.value().matches("[0-9]{1,4}"))
                    throw new IllegalArgumentException("Invalid expected count");
                if (a.kind() == BrowserKind.ASSERT_ROUTE && !a.value().startsWith("/"))
                    throw new IllegalArgumentException("Invalid expected route");
            } catch (RuntimeException invalid) {
                throw new AcceptanceStepException("browser", index + 1, a.id(), a.kind(), field, value, allowed, invalid);
            }
        }
        // Browser and API outcomes have separate namespaces (BROWSER_/API_). A feature may use the
        // same label in both; duplicate identities within either sequence remain invalid.
        ids.clear();
        Set<ApiKind> kinds = EnumSet.noneOf(ApiKind.class);
        Set<String> variables = new HashSet<>(Set.of("fixtureId", "fixtureEmail", "fixturePassword", "invalidToken"));
        Set<String> loginTokens = new HashSet<>();
        for (int index = 0; index < api.size(); index++) {
            var a = api.get(index);
            String field = "id", allowed = "must be a unique 1-64 character acceptance ID";
            Object value = a.id();
            try {
                id(a.id(), ids);
                field = "kind"; value = a.kind(); allowed = "must be one of " + Arrays.toString(ApiKind.values());
                Objects.requireNonNull(a.kind());
                kinds.add(a.kind());
                if (index == 0) {
                    field = "kind/method/path"; value = a.kind();
                    allowed = "must start with HEALTH GET on a literal local readiness path";
                    if (a.kind() != ApiKind.HEALTH || !"GET".equals(a.method())
                            || a.path() == null || a.path().contains("${"))
                        throw new IllegalArgumentException("API acceptance must start with a read-only readiness check");
                }
                field = "method"; value = a.method(); allowed = "must be GET, POST, PUT, PATCH or DELETE";
                if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(a.method()))
                    throw new IllegalArgumentException("Invalid acceptance HTTP method");
                field = "path"; value = a.path(); allowed = "must be a local /api/ request path with captured route variables";
                if (a.path() == null || !a.path().matches("/api/[A-Za-z0-9_./${}-]{1,240}(\\?[A-Za-z0-9_.~%=&${}-]{1,200})?") || a.path().contains("..")
                        || a.path().contains("//"))
                    throw new IllegalArgumentException(
                            "Invalid acceptance API path; expand route parameters with captured fixture variables");
                field = "status"; value = a.status(); allowed = "must be an HTTP status from 100 to 599";
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
                field = "method/status/tokenVariable"; value = a.method();
                allowed = "must match the acceptance kind: successful CRUD/health, NOT_FOUND 404, or auth denial 401/403";
                if (!semantics)
                    throw new IllegalArgumentException("Acceptance category cannot weaken required HTTP behavior");
                field = "body"; value = a.body(); allowed = "must be null or a JSON body string of at most 4000 characters";
                bounded(a.body(), 4000);
                field = "assertValue"; value = a.assertValue(); allowed = "must be null or scalar text of at most 512 characters";
                bounded(a.assertValue(), 512);
                field = "capturePointer"; value = a.capturePointer(); allowed = "must be null or a JSON pointer beginning /";
                pointer(a.capturePointer());
                field = "assertPointer"; value = a.assertPointer();
                pointer(a.assertPointer());
                field = "tokenVariable"; value = a.tokenVariable(); allowed = "must name a previously captured token variable";
                if (a.tokenVariable() != null && !variables.contains(a.tokenVariable()))
                    throw new IllegalArgumentException(
                            "Unknown token variable; use a previously captured name without dollar braces");
                allowed = "must name a previously captured login token for authenticated CRUD";
                if (authRequired && Set.of(ApiKind.CREATE, ApiKind.READ, ApiKind.UPDATE, ApiKind.DELETE, ApiKind.NOT_FOUND,
                        ApiKind.PERSISTENCE).contains(a.kind()) && !loginTokens.contains(a.tokenVariable()))
                    throw new IllegalArgumentException("Account acceptance requires the captured login token");
                for (String template : List.of(a.path(), a.body() == null ? "" : a.body(),
                        a.assertValue() == null ? "" : a.assertValue())) {
                    field = template.equals(a.path()) ? "path" : template.equals(a.body()) ? "body" : "assertValue";
                    value = template; allowed = "must use only fixture variables or previously captured identities";
                    var m = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}").matcher(template);
                    while (m.find())
                        if (!variables.contains(m.group(1)))
                            throw new IllegalArgumentException("Unknown fixture variable");
                }
                if (a.captureVariable() != null) {
                    field = "captureVariable/capturePointer"; value = a.captureVariable();
                    allowed = "must declare a unique variable name of at most 31 characters and a capturePointer";
                    if (!a.captureVariable().matches("[A-Za-z][A-Za-z0-9_]{0,30}") || !variables.add(a.captureVariable())
                            || a.capturePointer() == null)
                        throw new IllegalArgumentException("Invalid captured variable");
                }
                if (authRequired && (a.kind() == ApiKind.AUTH_SIGNUP || a.kind() == ApiKind.AUTH_LOGIN)) {
                    field = "body"; value = a.body(); allowed = "must use fixtureEmail and fixturePassword variables";
                    if (a.body() == null || !a.body().contains("${fixtureEmail}")
                            || !a.body().contains("${fixturePassword}"))
                        throw new IllegalArgumentException("Account acceptance must use unique fixture credentials");
                    if (a.kind() == ApiKind.AUTH_LOGIN) {
                        field = "captureVariable"; value = a.captureVariable(); allowed = "must capture the successful login token";
                        if (a.captureVariable() == null)
                            throw new IllegalArgumentException("Login token capture is required");
                        loginTokens.add(a.captureVariable());
                    }
                }
            } catch (RuntimeException invalid) {
                throw new AcceptanceStepException("api", index + 1, a.id(), a.kind(), field, value, allowed, invalid);
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
