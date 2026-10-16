package com.code.pal.agent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.project.capability.BuildDecisions;
import java.util.*;
import com.code.pal.project.brief.ProjectBriefFile;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Model-proposed data with nullable v2 metadata for historical v1 JSON. Validation never grants tools or raises budgets. */
public record BuildContract(int schemaVersion, UUID id, Stack stack, BuildDecisions.Storage storage,
        BuildDecisions.Auth auth, Limits limits, Map<String, String> theme, List<Route> routes,
        List<DataType> types, List<DataType> models, List<Api> api, List<Asset> assets,
        Set<String> envNames, List<Unit> units, BehaviorCriteria acceptance,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID specId,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long specRevision,
        @JsonInclude(JsonInclude.Include.NON_NULL) String specHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) Long planRevision,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID replacesContractId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String templateVersion,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ContractBatch> batches) {
    public BuildContract(int schemaVersion, UUID id, Stack stack, BuildDecisions.Storage storage,
            BuildDecisions.Auth auth, Limits limits, Map<String, String> theme, List<Route> routes,
            List<DataType> types, List<DataType> models, List<Api> api, List<Asset> assets,
            Set<String> envNames, List<Unit> units, BehaviorCriteria acceptance) {
        this(schemaVersion, id, stack, storage, auth, limits, theme, routes, types, models, api, assets,
                envNames, units, acceptance, null, null, null, null, null, null, List.of());
    }
    public enum Stack {
        STATIC, VITE, MERN
    }

    public enum Kind {
        STRING, NUMBER, BOOLEAN, JSON, STRING_ARRAY, NUMBER_ARRAY, JSON_ARRAY
    }

    public record Limits(int modelCalls, int toolCalls, int outputTokens, long timeoutMs) {
        public Limits {
            if (modelCalls < 1 || toolCalls < 1 || outputTokens < 1 || timeoutMs < 1)
                bad("Invalid fixed limits");
        }

        public boolean within(Limits approved) {
            return modelCalls <= approved.modelCalls && toolCalls <= approved.toolCalls
                    && outputTokens <= approved.outputTokens && timeoutMs <= approved.timeoutMs;
        }
    }

    public record Field(String name, Kind kind, boolean optional) {
        public Field {
            identifier(name);
            Objects.requireNonNull(kind);
        }
    }

    public record DataType(String name, List<Field> fields) {
        public DataType {
            identifier(name);
            fields = bounded(fields, 24);
            if (fields.isEmpty())
                bad("Empty type");
            unique(fields.stream().map(Field::name).toList());
        }
    }

    public record Route(String path, String componentPath, String owner) {
        public Route {
            if (path == null || !path.matches("/[A-Za-z0-9_/:.-]{0,120}") || path.contains("..") || path.contains("//"))
                bad("Invalid frontend route");
            canonical(componentPath);
            identifier(owner);
        }
    }

    public record Api(String id, String method, String path, String requestType, String responseType, String owner,
            String routerPath, String routerExport, String basePath, boolean authenticated) {
        public Api {
            identifier(id);
            identifier(owner);
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method) || path == null
                    || !path.matches("/api/[A-Za-z0-9_/:-]{1,180}") || path.contains("..") || path.contains("//"))
                bad("Invalid API shape");
            typeRef(requestType, true);
            typeRef(responseType, false);
            canonical(routerPath);
            identifier(routerExport);
            if (basePath != null)
                basePath = basePath.replaceFirst("/+$", "");
            if (basePath == null || !(basePath.equals("/api") || basePath.matches("/api/[A-Za-z0-9_/-]{1,100}"))
                    || basePath.contains("//"))
                bad("Invalid API mount path; use /api or /api/name");
            if (!(path.equals(basePath) || path.startsWith(basePath + "/")))
                bad("API endpoint is outside its declared router mount");
        }
    }

    public record Asset(String source, String destination, String fingerprint, String owner) {
        public Asset {
            canonical(source);
            canonical(destination);
            identifier(owner);
            if (fingerprint == null || !fingerprint.matches("sha256:[a-f0-9]{64}"))
                bad("Asset identity missing");
        }
    }

    public record Unit(String id, String objective, List<String> ownedPaths, List<String> imports,
            List<String> dependsOn, List<String> acceptanceIds) {
        public Unit {
            identifier(id);
            text(objective, 1400);
            ownedPaths = bounded(ownedPaths, 12);
            imports = bounded(imports, 20);
            dependsOn = bounded(dependsOn, 12);
            acceptanceIds = bounded(acceptanceIds, 24);
            if (ownedPaths.isEmpty())
                bad("A unit must own files");
            ownedPaths.forEach(BuildContract::canonical);
            imports.forEach(BuildContract::canonical);
            dependsOn.forEach(BuildContract::identifier);
            unique(ownedPaths);
            unique(dependsOn);
            unique(acceptanceIds);
        }
    }

    public BuildContract {
        if (schemaVersion != 1 && schemaVersion != 2)
            bad("Unsupported contract version");
        Objects.requireNonNull(id);
        Objects.requireNonNull(stack);
        Objects.requireNonNull(storage);
        Objects.requireNonNull(auth);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(acceptance);
        if (schemaVersion == 1 && acceptance.modelSpec()) {
            bad("Contract acceptance cannot opt into server-owned model-spec advisory provenance");
        }
        theme = Map.copyOf(theme);
        routes = bounded(routes, 20);
        types = bounded(types, 16);
        models = bounded(models, 12);
        api = bounded(api, 24);
        assets = bounded(assets, 16);
        envNames = Set.copyOf(envNames);
        units = bounded(units, 12);
        if (units.isEmpty() || theme.isEmpty() || theme.size() > 8 || envNames.size() > 8)
            bad("Contract size invalid");
        for (var token : theme.entrySet()) {
            if (!Set.of("background", "foreground", "accent", "muted", "radius", "spacing", "font")
                    .contains(token.getKey()))
                bad("Unsupported theme token");
            boolean valid = switch (token.getKey()) {
            case "radius", "spacing" -> token.getValue().matches("(?:[0-9]|[12][0-9]|3[0-2])px");
            case "font" -> Set.of("system", "serif", "mono").contains(token.getValue());
            default -> token.getValue().matches("#(?:[a-fA-F0-9]{3}|[a-fA-F0-9]{4}|[a-fA-F0-9]{6}|[a-fA-F0-9]{8})");
            };
            if (!valid)
                bad("Unsafe theme value");
        }
        for (String env : envNames)
            if (!Set.of("MONGODB_URI", "PORT", "NODE_ENV", "VITE_SUPABASE_URL", "VITE_SUPABASE_ANON_KEY").contains(env))
                bad("Unsupported environment name");
        if ((stack == Stack.MERN) != (storage == BuildDecisions.Storage.MERN))
            bad("Stack/storage mismatch");
        if (stack != Stack.MERN && (!api.isEmpty() || !models.isEmpty()))
            bad("Local APIs/models require MERN");
        if (auth == BuildDecisions.Auth.ACCOUNTS && !acceptance.authRequired())
            bad("Accounts require auth acceptance");
        if (stack == Stack.MERN
                && (api.isEmpty() || acceptance.api().isEmpty() || !acceptance.crudRequired() || models.isEmpty()))
            bad("MERN data needs API/CRUD/persistence acceptance");
        if (acceptance.browser().isEmpty()
                || acceptance.browser().stream()
                        .noneMatch(a -> Set.of(BehaviorCriteria.BrowserKind.ASSERT_TEXT,
                                BehaviorCriteria.BrowserKind.ASSERT_COUNT, BehaviorCriteria.BrowserKind.ASSERT_EXISTS,
                                BehaviorCriteria.BrowserKind.ASSERT_ROUTE)
                                .contains(a.kind())))
            bad("Frontend assertions missing");
        if (storage != BuildDecisions.Storage.SUPABASE
                && envNames.stream().anyMatch(s -> s.startsWith("VITE_SUPABASE")))
            bad("Unapproved connector hook");
        if (stack != Stack.MERN
                && envNames.stream().anyMatch(s -> Set.of("MONGODB_URI", "PORT", "NODE_ENV").contains(s)))
            bad("Unused server environment hook");
        if (storage == BuildDecisions.Storage.SUPABASE
                && (stack != Stack.VITE || !envNames.equals(Set.of("VITE_SUPABASE_URL", "VITE_SUPABASE_ANON_KEY"))))
            bad("Connected Supabase requires the Vite connector hook");
        unique(units.stream().map(Unit::id).toList());
        unique(types.stream().map(DataType::name).toList());
        unique(models.stream().map(DataType::name).toList());
        unique(routes.stream().map(Route::path).toList());
        unique(api.stream().map(Api::id).toList());
        unique(api.stream().map(a -> a.method() + " " + a.path()).toList());
        var ownership = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        var unitIds = new HashSet<>(units.stream().map(Unit::id).toList());
        String clientRoot = clientRoot(stack, id), serverRoot = serverRoot(id);
        for (var unit : units)
            for (String path : unit.ownedPaths()) {
                if (path.toLowerCase(Locale.ROOT).startsWith(clientRoot.toLowerCase(Locale.ROOT))
                        || path.toLowerCase(Locale.ROOT).startsWith(serverRoot.toLowerCase(Locale.ROOT))
                        || reserved(path, stack)
                        || (schemaVersion == 2 && (path.equalsIgnoreCase(StaticAssets.MARKER) || path.equalsIgnoreCase("rendra.css")))
                        || ownership.putIfAbsent(path, unit.id()) != null)
                    bad("Overlapping/coordinator file ownership");
            }
        for (String path : ownership.keySet())
            for (String other : ownership.keySet())
                if (other.toLowerCase(Locale.ROOT).startsWith(path.toLowerCase(Locale.ROOT) + "/"))
                    bad("File/parent ownership conflict");
        var ids = new HashSet<String>();
        acceptance.browser().forEach(a -> ids.add(a.id()));
        acceptance.api().forEach(a -> ids.add(a.id()));
        for (var unit : units) {
            if (!unitIds.containsAll(unit.dependsOn()) || unit.dependsOn().contains(unit.id())
                    || !ids.containsAll(unit.acceptanceIds()))
                bad("Unknown dependency/acceptance criterion");
        }
        var assigned = new HashSet<String>();
        units.forEach(u -> assigned.addAll(u.acceptanceIds()));
        if (!assigned.containsAll(ids))
            bad("Acceptance criterion has no responsible unit");
        for (var route : routes)
            if (!route.owner().equals(ownership.get(route.componentPath())))
                bad("Route ownership mismatch");
        var typeNames = new HashSet<>(types.stream().map(DataType::name).toList());
        typeNames.addAll(Set.of("ApiError", "AuthSession", "Credentials", "unknown"));
        var routers = new HashMap<String, String>();
        for (var endpoint : api) {
            if (!endpoint.routerPath().startsWith("server/src/") || !endpoint.routerPath().endsWith(".ts"))
                bad("MERN router must be a server TypeScript module");
            if (auth == BuildDecisions.Auth.ACCOUNTS && !endpoint.authenticated())
                bad("Account data APIs require the coordinator auth guard");
            if (auth == BuildDecisions.Auth.ACCOUNTS && endpoint.path().startsWith("/api/auth/"))
                bad("Authentication routes are coordinator-owned");
            if (!endpoint.owner().equals(ownership.get(endpoint.routerPath())))
                bad("API router ownership mismatch");
            if (!typeNames.contains(baseType(endpoint.responseType())))
                bad("Unknown API response type " + endpoint.responseType() + "; declare its base name in types");
            if (endpoint.requestType() != null && !typeNames.contains(baseType(endpoint.requestType())))
                bad("Unknown API request type " + endpoint.requestType() + "; declare its base name in types");
            String signature = endpoint.basePath() + "/" + endpoint.routerExport();
            String prior = routers.putIfAbsent(endpoint.routerPath(), signature);
            if (prior != null && !prior.equals(signature))
                bad("Inconsistent router interface");
            if (endpoint.authenticated() && auth != BuildDecisions.Auth.ACCOUNTS)
                bad("API auth capability mismatch");
            if (acceptance.api().stream().noneMatch(step -> step.method().equals(endpoint.method())
                    && step.status() >= 200 && step.status() < 300 && matchesApiPath(endpoint.path(), step.path()))) {
                String requests = acceptance.api().stream()
                        .filter(step -> step.method().equals(endpoint.method()) && step.status() >= 200
                                && step.status() < 300 && step.kind() != BehaviorCriteria.ApiKind.HEALTH)
                        .map(step -> step.path().replaceAll("\\$\\{[A-Za-z0-9_]+}", ":fixture")).distinct().limit(2)
                        .map(path -> path.substring(0, Math.min(45, path.length()))).collect(Collectors.joining(","));
                bad("Declared API " + endpoint.method() + " " + endpoint.path()
                        + " has no successful acceptance request; tested paths " + requests);
            }
        }
        unique(api.stream().collect(Collectors.toMap(Api::routerPath, Api::routerExport, (a, b) -> a)).values().stream()
                .toList());
        unique(api.stream().collect(Collectors.toMap(Api::routerPath, Api::basePath, (a, b) -> a)).values().stream()
                .toList());
        if (auth == BuildDecisions.Auth.ACCOUNTS && stack == Stack.MERN) {
            boolean missing = acceptance.api().stream().anyMatch(a -> a.kind() == BehaviorCriteria.ApiKind.AUTH_REJECT
                    && a.method().equals("GET") && a.tokenVariable() == null);
            boolean wrongPassword = acceptance.api().stream()
                    .anyMatch(a -> a.kind() == BehaviorCriteria.ApiKind.AUTH_REJECT && a.method().equals("POST")
                            && a.path().equals("/api/auth/login") && a.body() != null
                            && a.body().contains("${fixtureEmail}") && a.body().contains("${invalidToken}"));
            if (!missing || !wrongPassword)
                bad("Authentication requires missing-token and wrong-password denial");
            for (var step : acceptance.api())
                if (step.kind() == BehaviorCriteria.ApiKind.AUTH_SIGNUP && !step.path().equals("/api/auth/signup")
                        || step.kind() == BehaviorCriteria.ApiKind.AUTH_LOGIN && !step.path().equals("/api/auth/login"))
                    bad("Use the coordinator's canonical authentication API");
        }
        for (var step : acceptance.api())
            if (Set.of(BehaviorCriteria.ApiKind.READ, BehaviorCriteria.ApiKind.UPDATE,
                    BehaviorCriteria.ApiKind.PERSISTENCE).contains(step.kind())
                    && (step.assertPointer() == null || step.assertValue() == null))
                bad("Data behavior requires value assertions");
        unique(assets.stream().map(Asset::destination).toList());
        for (var asset : assets)
            if (!asset.owner().equals(ownership.get(asset.destination())))
                bad("Asset destination ownership mismatch");
        for (var unit : units)
            for (String path : unit.imports()) {
                String owner = ownership.get(path);
                if (owner != null && !owner.equals(unit.id()) && !unit.dependsOn().contains(owner))
                    bad("Cross-unit import needs dependency");
            }
        // The constructor cannot use record accessors until assignment; validate DAG directly.
        waves(units);
        batches = batches == null ? List.of() : List.copyOf(batches);
        if (schemaVersion == 1) {
            if (specId != null || (specRevision != null && specRevision != 0) || specHash != null
                    || (planRevision != null && planRevision != 0)
                    || replacesContractId != null || templateVersion != null || !batches.isEmpty()) {
                bad("Legacy contract cannot carry partial versioned metadata");
            }
            specRevision = null;
            planRevision = null;
        } else {
            if (specId == null || specRevision == null || specRevision < 1 || specHash == null || !specHash.matches("sha256:[a-f0-9]{64}")
                    || planRevision == null || planRevision < 1 || templateVersion == null || templateVersion.isBlank()
                    || templateVersion.length() > 80 || id.equals(replacesContractId)
                    || (planRevision == 1) != (replacesContractId == null)) {
                bad("Invalid versioned plan identity");
            }
            ContractPlans.validateBatches(units, batches, limits);
        }
    }

    public String clientRoot() {
        return clientRoot(stack, id);
    }

    public String serverRoot() {
        return serverRoot(id);
    }

    public List<List<Unit>> waves() {
        return waves(units);
    }

    public Map<String, String> ownership() {
        var result = new TreeMap<String, String>();
        units.forEach(u -> u.ownedPaths().forEach(p -> result.put(p, u.id())));
        return Map.copyOf(result);
    }

    public static List<List<Unit>> waves(List<Unit> units) {
        var done = new HashSet<String>();
        var result = new ArrayList<List<Unit>>();
        while (done.size() < units.size()) {
            var wave = units.stream().filter(u -> !done.contains(u.id()) && done.containsAll(u.dependsOn())).toList();
            if (wave.isEmpty())
                bad("Cyclic unit graph");
            result.add(wave);
            wave.forEach(u -> done.add(u.id()));
        }
        return List.copyOf(result);
    }

    public static String canonical(String path) {
        if (path == null || !path.matches("[\\p{L}\\p{N}_ ./-]{1,240}")
                || !ProjectPaths.agentPath(path, false).equals(path)
                || !Path.of(path).normalize().toString().replace('\\', '/').equals(path)
                || ProjectBriefFile.privatePath(path)
                || Arrays.stream(path.split("/")).anyMatch(s -> s.startsWith(".") || s.endsWith(".") || s.endsWith(" ")
                        || Set.of("node_modules", "dist", "build").contains(s.toLowerCase(Locale.ROOT))))
            bad("Noncanonical/protected contract path");
        return path;
    }

    public static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_]{0,47}")
                || Set.of("__proto__", "constructor", "prototype", "default", "class", "function", "import", "export",
                        "return", "var", "let", "const", "new").contains(value))
            bad("Invalid identifier");
    }

    private static boolean reserved(String path, Stack stack) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith("package.json") || p.endsWith("package-lock.json") || p.endsWith("tsconfig.json")
                || p.endsWith("vite.config.ts") || p.equals("starter-runtime.json")
                || (stack == Stack.MERN && p.equals("server/src/index.ts"));
    }

    private static String clientRoot(Stack stack, UUID id) {
        return (stack == Stack.STATIC ? "rendra/" : stack == Stack.MERN ? "client/src/rendra/" : "src/rendra/") + id
                + "/";
    }

    private static String serverRoot(UUID id) {
        return "server/src/rendra/" + id + "/";
    }

    private static void typeRef(String value, boolean nullable) {
        if (nullable && value == null)
            return;
        identifier(baseType(value));
    }

    private static String baseType(String value) {
        return value != null && value.endsWith("[]") ? value.substring(0, value.length() - 2) : value;
    }

    static boolean matchesApiPath(String shape, String actual) {
        String regex = Arrays.stream(shape.split("/", -1))
                .map(part -> part.startsWith(":") ? "(?:\\$\\{[A-Za-z][A-Za-z0-9_]*\\}|[A-Za-z0-9_-]+)"
                        : Pattern.quote(part))
                .collect(Collectors.joining("/"));
        return actual.matches(regex);
    }

    private static <T> List<T> bounded(List<T> values, int max) {
        if (values == null || values.size() > max || values.stream().anyMatch(Objects::isNull))
            bad("Contract collection exceeds bounds");
        return List.copyOf(values);
    }

    private static void unique(List<String> values) {
        if (new HashSet<>(values).size() != values.size())
            bad("Duplicate contract identifiers");
    }

    private static void text(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            bad("Contract text exceeds bounds");
    }

    private static void bad(String reason) {
        throw new IllegalArgumentException(reason);
    }
}
