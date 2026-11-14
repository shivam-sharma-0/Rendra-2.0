package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.agent.validation.BehaviorCriteria.ApiKind;
import com.code.pal.agent.validation.BehaviorCriteria.ApiStep;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserAction;
import com.code.pal.agent.validation.BehaviorCriteria.BrowserKind;
import com.code.pal.project.capability.BuildDecisions;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class BlueprintCompiler {
    public static final String ACCOUNT_UNIT = "platform_account";
    static final String LOGIN_TOKEN = "platformLoginToken";
    private static final Map<String, Blueprint.Kind> KINDS = kinds();
    private static final Map<String, Blueprint.PageKind> PAGE_KINDS = pageKinds();

    private BlueprintCompiler() {
    }

    public static Blueprint normalize(Map<?, ?> raw) {
        var later = new ArrayList<>(texts(raw.get("later"), 160));
        var features = new ArrayList<Blueprint.Feature>();
        for (var value : list(raw.get("features"))) {
            var item = map(value);
            String id = slug(text(item.get("id"), 40, null));
            String description = text(item.get("description"), 600, null);
            if (id != null && description != null && features.size() < Blueprint.FEATURES
                    && features.stream().noneMatch(feature -> feature.id().equals(id)))
                features.add(new Blueprint.Feature(id, description));
        }
        var entities = new ArrayList<Blueprint.Entity>();
        for (var value : list(raw.get("entities"))) {
            var entity = entity(map(value));
            if (entity == null || entities.stream().anyMatch(e -> e.name().equalsIgnoreCase(entity.name())
                    || e.plural().equalsIgnoreCase(entity.plural())))
                continue;
            if (entities.size() == Blueprint.ENTITIES)
                later.add("Manage " + entity.pluralLabel().toLowerCase(Locale.ROOT));
            else
                entities.add(entity);
        }
        var pages = pages(list(raw.get("pages")), entities, features, later);
        while (later.size() > Blueprint.LATER)
            later.removeLast();
        String preset = DesignPresets.name(text(raw.get("preset"), 40, null));
        String name = text(raw.get("name"), 60, "App");
        String icon = Blueprint.ICONS.contains(text(raw.get("icon"), 40, "")) ? text(raw.get("icon"), 40, "")
                : iconFor(name, entities.isEmpty() ? "Sparkles" : entities.getFirst().icon());
        return new Blueprint(name, icon, preset == null ? "clean" : preset, color(raw.get("accent")),
                entities, pages, features, later.stream().distinct().limit(Blueprint.LATER).toList());
    }

    public static BuildContract compile(Blueprint blueprint, UUID id, BuildDecisions.Choice choice, BuildContract.Limits limits) {
        return compile(blueprint, id, choice, limits, List.of("/sign-in"));
    }

    public static BuildContract compile(Blueprint blueprint, UUID id, BuildDecisions.Choice choice, BuildContract.Limits limits,
            List<String> signInPaths) {
        if (signInPaths.isEmpty() || signInPaths.stream().anyMatch(path -> !ApprovedProductScope.signInRoute(path) || path.contains(":")
                || blueprint.pages().stream().anyMatch(page -> page.path().equals(path))))
            throw new IllegalArgumentException("Sign-in paths must be free sign-in routes");
        if (choice.storage() == BuildDecisions.Storage.SUPABASE)
            throw new IllegalArgumentException("Blueprint builds do not support connected Supabase storage yet");
        boolean mern = choice.storage() == BuildDecisions.Storage.MERN;
        if (!mern && choice.auth() == BuildDecisions.Auth.ACCOUNTS)
            throw new IllegalArgumentException("Accounts need the app server");
        if (mern && blueprint.entities().isEmpty())
            throw new IllegalArgumentException("A server app needs at least one kind of record");
        boolean accounts = mern && choice.auth() == BuildDecisions.Auth.ACCOUNTS;
        String pages = (mern ? "client/src/" : "src/") + "pages/";
        var stack = mern ? BuildContract.Stack.MERN : BuildContract.Stack.VITE;
        var routes = new ArrayList<BuildContract.Route>();
        var owned = new LinkedHashMap<String, List<String>>();
        var objectives = new LinkedHashMap<String, String>();
        for (var entity : blueprint.entities()) {
            var paths = new ArrayList<String>();
            if (mern)
                paths.add(routerPath(entity));
            for (var kind : List.of(Blueprint.PageKind.LIST, Blueprint.PageKind.DETAIL)) {
                var page = blueprint.page(entity.name(), kind);
                if (page == null)
                    continue;
                String component = pages + entity.name() + (kind == Blueprint.PageKind.LIST ? "ListPage.tsx" : "DetailPage.tsx");
                paths.add(component);
                routes.add(new BuildContract.Route(page.path(), component, crudUnit(entity)));
            }
            if (!paths.isEmpty()) {
                owned.put(crudUnit(entity), paths);
                objectives.put(crudUnit(entity), "Platform-generated " + entity.pluralLabel().toLowerCase(Locale.ROOT)
                        + (mern ? " API and pages" : " pages") + "; written by the platform, not the model");
            }
        }
        if (accounts) {
            String signIn = pages + "SignInPage.tsx", settings = pages + "AccountSettingsPage.tsx";
            owned.put(ACCOUNT_UNIT, List.of(signIn, settings));
            objectives.put(ACCOUNT_UNIT, "Platform sign-in and account pages; written by the platform, not the model");
            for (String path : signInPaths)
                routes.add(new BuildContract.Route(path, signIn, ACCOUNT_UNIT));
            routes.add(new BuildContract.Route(Blueprint.ACCOUNT_PATH, settings, ACCOUNT_UNIT));
        }
        var usedComponents = routes.stream().map(route -> route.componentPath().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        for (var page : blueprint.pages()) {
            if (page.kind().generated())
                continue;
            String base = Blueprint.upper(identifierWords(page.title()));
            if (base.isEmpty() || !Character.isLetter(base.charAt(0)))
                base = page.path().equals("/") ? "Home" : "Screen";
            base = base.substring(0, Math.min(36, base.length()));
            String component = pages + base + "Page.tsx";
            for (int suffix = 2; usedComponents.contains(component.toLowerCase(Locale.ROOT)); suffix++)
                component = pages + base + suffix + "Page.tsx";
            usedComponents.add(component.toLowerCase(Locale.ROOT));
            String unit = "page_" + Blueprint.lower(component.substring(pages.length(), component.length() - "Page.tsx".length()));
            owned.put(unit, List.of(component));
            objectives.put(unit, objective(blueprint, page, mern));
            routes.add(new BuildContract.Route(page.path(), component, unit));
        }
        var order = blueprint.pages().stream().map(Blueprint.Page::path).toList();
        routes.sort(Comparator.comparingInt(route -> order.contains(route.path()) ? order.indexOf(route.path()) : order.size()));
        var types = new ArrayList<BuildContract.DataType>();
        var models = new ArrayList<BuildContract.DataType>();
        var api = new ArrayList<BuildContract.Api>();
        for (var entity : blueprint.entities()) {
            var stored = entity.fields().stream().map(field -> new BuildContract.Field(field.name(), kind(field.kind()), !field.required())).toList();
            var record = new ArrayList<BuildContract.Field>();
            record.add(new BuildContract.Field("id", BuildContract.Kind.STRING, false));
            record.addAll(stored);
            if (accounts)
                record.add(new BuildContract.Field("accountId", BuildContract.Kind.STRING, true));
            record.add(new BuildContract.Field("createdAt", BuildContract.Kind.STRING, true));
            record.add(new BuildContract.Field("updatedAt", BuildContract.Kind.STRING, true));
            types.add(new BuildContract.DataType(entity.name(), record));
            types.add(new BuildContract.DataType(entity.name() + "Input", stored));
            if (!mern)
                continue;
            models.add(new BuildContract.DataType(entity.name(), stored));
            String base = entity.apiPath(), unit = crudUnit(entity), router = routerPath(entity), export = entity.plural() + "Router";
            api.add(new BuildContract.Api("list" + Blueprint.upper(entity.plural()), "GET", base, null, entity.name() + "[]", unit, router, export, base, accounts));
            api.add(new BuildContract.Api("get" + entity.name(), "GET", base + "/:id", null, entity.name(), unit, router, export, base, accounts));
            api.add(new BuildContract.Api("create" + entity.name(), "POST", base, entity.name() + "Input", entity.name(), unit, router, export, base, accounts));
            api.add(new BuildContract.Api("update" + entity.name(), "PATCH", base + "/:id", entity.name() + "Input", entity.name(), unit, router, export, base, accounts));
            api.add(new BuildContract.Api("delete" + entity.name(), "DELETE", base + "/:id", null, "unknown", unit, router, export, base, accounts));
        }
        var checks = new LinkedHashMap<String, List<String>>();
        owned.keySet().forEach(unit -> checks.put(unit, new ArrayList<>()));
        var apiSteps = mern ? apiChecks(blueprint, accounts, checks) : List.<ApiStep>of();
        var browser = browserChecks(blueprint, routes, checks);
        var units = new ArrayList<BuildContract.Unit>();
        owned.forEach((unit, paths) -> units.add(new BuildContract.Unit(unit, objectives.get(unit), paths, List.of(), List.of(), checks.get(unit))));
        var theme = new LinkedHashMap<String, String>();
        theme.put("preset", blueprint.preset());
        if (blueprint.accent() != null)
            theme.put("accent", blueprint.accent());
        return new BuildContract(1, id, stack, choice.storage(), accounts ? BuildDecisions.Auth.ACCOUNTS : BuildDecisions.Auth.NONE,
                limits, theme, routes, types, models, api, List.of(), mern ? Set.of("MONGODB_URI") : Set.of(), units,
                new BehaviorCriteria(1, browser, apiSteps, accounts, mern), null, null, null, null, null, null, List.of(), blueprint);
    }

    static String crudUnit(Blueprint.Entity entity) {
        return "crud_" + entity.plural();
    }

    static String routerPath(Blueprint.Entity entity) {
        return "server/src/routers/" + entity.plural() + ".ts";
    }

    private static List<ApiStep> apiChecks(Blueprint blueprint, boolean accounts, Map<String, List<String>> checks) {
        var steps = new ArrayList<ApiStep>();
        var first = blueprint.entities().getFirst();
        String setup = accounts ? ACCOUNT_UNIT : crudUnit(first);
        String token = accounts ? LOGIN_TOKEN : null;
        add(steps, checks, setup, new ApiStep("platform_health", ApiKind.HEALTH, "GET", "/api/health", null, 200, null, null, null, null, null));
        if (accounts) {
            String credentials = "{\"email\":\"${fixtureEmail}\",\"password\":\"${fixturePassword}\"}";
            add(steps, checks, setup, new ApiStep("platform_signup", ApiKind.AUTH_SIGNUP, "POST", "/api/auth/signup", credentials, 201, null, null, null, null, null));
            add(steps, checks, setup, new ApiStep("platform_login", ApiKind.AUTH_LOGIN, "POST", "/api/auth/login", credentials, 200, null, LOGIN_TOKEN, "/token", null, null));
            add(steps, checks, setup, new ApiStep("platform_wrong_password", ApiKind.AUTH_REJECT, "POST", "/api/auth/login",
                    "{\"email\":\"${fixtureEmail}\",\"password\":\"${invalidToken}\"}", 401, null, null, null, null, null));
            add(steps, checks, setup, new ApiStep("platform_missing_token", ApiKind.AUTH_REJECT, "GET", first.apiPath(), null, 401, null, null, null, null, null));
            add(steps, checks, setup, new ApiStep("platform_invalid_token", ApiKind.AUTH_INVALID, "GET", first.apiPath(), null, 401, "invalidToken", null, null, null, null));
        }
        int searches = BehaviorCriteria.API_CAP - steps.size() - 1 - blueprint.entities().size() * 6;
        for (var entity : blueprint.entities()) {
            String unit = crudUnit(entity), base = entity.apiPath(), variable = entity.variable() + "Id", item = base + "/${" + variable + "}";
            String display = entity.display(), pointer = "/" + display;
            String created = "Sample " + entity.label().toLowerCase(Locale.ROOT), updated = "Updated " + entity.label().toLowerCase(Locale.ROOT);
            String p = entity.plural();
            add(steps, checks, unit, new ApiStep(p + "_create", ApiKind.CREATE, "POST", base, sample(entity, created), 201, token, variable, "/id", pointer, created));
            add(steps, checks, unit, new ApiStep(p + "_list", ApiKind.READ, "GET", base, null, 200, token, null, null, "/0" + pointer, created));
            add(steps, checks, unit, new ApiStep(p + "_read", ApiKind.READ, "GET", item, null, 200, token, null, null, pointer, created));
            if (!entity.search().isEmpty() && entity.search().contains(display) && searches-- > 0)
                add(steps, checks, unit, new ApiStep(p + "_search", ApiKind.READ, "GET", base + "?q=sample", null, 200, token, null, null, "/0" + pointer, created));
            add(steps, checks, unit, new ApiStep(p + "_update", ApiKind.UPDATE, "PATCH", item, "{" + quote(display) + ":" + quote(updated) + "}", 200, token, null, null, pointer, updated));
            if (entity == first)
                add(steps, checks, unit, new ApiStep(p + "_persisted", ApiKind.PERSISTENCE, "GET", item, null, 200, token, null, null, pointer, updated));
            add(steps, checks, unit, new ApiStep(p + "_delete", ApiKind.DELETE, "DELETE", item, null, 200, token, null, null, null, null));
            add(steps, checks, unit, new ApiStep(p + "_missing", ApiKind.NOT_FOUND, "GET", item, null, 404, token, null, null, null, null));
        }
        return steps;
    }

    private static List<BrowserAction> browserChecks(Blueprint blueprint, List<BuildContract.Route> routes, Map<String, List<String>> checks) {
        var groups = new ArrayList<Map.Entry<String, List<BrowserAction>>>();
        String home = routes.stream().filter(route -> route.path().equals("/")).map(BuildContract.Route::owner).findFirst().orElseThrow();
        groups.add(Map.entry(home, List.of(new BrowserAction("home_renders", BrowserKind.ASSERT_EXISTS, ".app-main > *", null))));
        boolean created = false;
        for (var entity : blueprint.entities()) {
            String list = blueprint.listPath(entity);
            if (list == null)
                continue;
            String p = entity.plural();
            var steps = new ArrayList<BrowserAction>();
            if (!list.equals("/"))
                steps.add(new BrowserAction(p + "_open", BrowserKind.CLICK, "a[href=\"#" + list + "\"]", null));
            steps.add(new BrowserAction(p + "_page", BrowserKind.ASSERT_EXISTS, "[data-crud=\"" + p + "\"]", null));
            if (!created) {
                created = true;
                String value = "Browser " + entity.label().toLowerCase(Locale.ROOT);
                steps.add(new BrowserAction(p + "_new", BrowserKind.CLICK, "#" + p + "-new", null));
                int index = 1;
                for (var field : entity.fields())
                    if (field.required() && field.textual())
                        steps.add(new BrowserAction(p + "_fill" + index++, BrowserKind.FILL, "#" + p + "-" + field.name(),
                                field.name().equals(entity.display()) ? value : "Browser " + Blueprint.words(field.name()).toLowerCase(Locale.ROOT)));
                steps.add(new BrowserAction(p + "_save", BrowserKind.CLICK, "#" + p + "-save", null));
                steps.add(new BrowserAction(p + "_created", BrowserKind.ASSERT_TEXT, "[data-crud=\"" + p + "\"] [data-records]", value));
            }
            groups.add(Map.entry(crudUnit(entity), steps));
        }
        for (var route : routes)
            if (route.owner().startsWith("page_") && !route.path().equals("/") && !route.path().contains(":")) {
                String id = route.owner().substring("page_".length());
                groups.add(Map.entry(route.owner(), List.of(
                        new BrowserAction(id + "_open", BrowserKind.CLICK, "a[href=\"#" + route.path() + "\"]", null),
                        new BrowserAction(id + "_route", BrowserKind.ASSERT_ROUTE, null, route.path()),
                        new BrowserAction(id + "_renders", BrowserKind.ASSERT_EXISTS, ".app-main > *", null))));
            }
        var browser = new ArrayList<BrowserAction>();
        for (var group : groups) {
            if (browser.size() + group.getValue().size() > BehaviorCriteria.BROWSER_CAP)
                continue;
            browser.addAll(group.getValue());
            group.getValue().forEach(action -> checks.get(group.getKey()).add(action.id()));
        }
        return browser;
    }

    private static void add(List<ApiStep> steps, Map<String, List<String>> checks, String unit, ApiStep step) {
        steps.add(step);
        checks.get(unit).add(step.id());
    }

    private static String sample(Blueprint.Entity entity, String display) {
        var body = new StringJoiner(",", "{", "}");
        for (var field : entity.fields()) {
            if (!field.required())
                continue;
            String value = field.name().equals(entity.display()) ? quote(display) : switch (field.kind()) {
            case STRING -> quote("Sample " + Blueprint.words(field.name()).toLowerCase(Locale.ROOT));
            case TEXT -> quote("Sample notes");
            case NUMBER -> "3";
            case BOOLEAN -> "true";
            case DATE -> quote("2026-01-15");
            case ENUM -> quote(field.options().getFirst());
            case IMAGE -> quote("https://images.example/sample.jpg");
            };
            body.add(quote(field.name()) + ":" + value);
        }
        return body.toString();
    }

    private static String quote(String text) {
        var out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\')
                out.append('\\').append(c);
            else if (c < 0x20)
                out.append(String.format("\\u%04x", (int) c));
            else
                out.append(c);
        }
        return out.append('"').toString();
    }

    private static String objective(Blueprint blueprint, Blueprint.Page page, boolean mern) {
        var text = new StringBuilder("Write the ").append(page.title()).append(" page at route ").append(page.path()).append(". ");
        text.append(switch (page.kind()) {
        case LANDING -> "Landing page of " + blueprint.name() + ": a hero with the product promise, the main things people can do and clear calls to action.";
        case DASHBOARD -> "Dashboard: live stats, recent items and quick links built from the app's data.";
        case SETTINGS -> "Settings for this app's preferences.";
        default -> "";
        });
        if (page.description() != null)
            text.append(' ').append(page.description());
        var feature = page.feature() == null ? null : blueprint.feature(page.feature());
        if (feature != null)
            text.append(" Feature: ").append(feature.description());
        if (page.path().contains(":"))
            text.append(" Route params arrive as props.params.");
        if (page.entity() != null)
            text.append(" Main data: ").append(page.entity()).append(".");
        if (!blueprint.entities().isEmpty())
            text.append(" Read and change records only through the generated specs in entities.ts (")
                    .append(blueprint.entities().stream().map(entity -> entity.plural() + ".source")
                            .collect(Collectors.joining(", ")))
                    .append(mern ? ", backed by the API" : ", saved in the browser")
                    .append("); link to generated pages instead of rebuilding them.");
        text.append(" Use the UI kit.");
        return text.length() <= 1400 ? text.toString() : text.substring(0, 1400);
    }

    private static BuildContract.Kind kind(Blueprint.Kind kind) {
        return switch (kind) {
        case NUMBER -> BuildContract.Kind.NUMBER;
        case BOOLEAN -> BuildContract.Kind.BOOLEAN;
        default -> BuildContract.Kind.STRING;
        };
    }

    private static Blueprint.Entity entity(Map<?, ?> raw) {
        String name = Blueprint.upper(identifierWords(text(raw.get("name"), 80, "")));
        if (name.isEmpty() || !Character.isLetter(name.charAt(0)))
            return null;
        name = name.substring(0, Math.min(36, name.length()));
        if (BuildContract.BUILT_IN_TYPES.contains(name))
            name = name + "Item";
        String plural = Blueprint.lower(identifierWords(text(raw.get("plural"), 80, "")));
        if (plural.isEmpty() || !Character.isLetter(plural.charAt(0)))
            plural = pluralOf(Blueprint.lower(name));
        plural = plural.substring(0, Math.min(40, plural.length()));
        if (Blueprint.RESERVED_PLURALS.contains(plural))
            plural = plural + "Items";
        var fields = new ArrayList<Blueprint.Field>();
        for (var value : list(raw.get("fields"))) {
            var item = value instanceof String text ? Map.of("name", text) : map(value);
            String field = Blueprint.lower(identifierWords(text(item.get("name"), 80, "")));
            if (field.isEmpty() || !Character.isLetter(field.charAt(0)) || Blueprint.RESERVED_FIELDS.contains(field)
                    || fields.size() == Blueprint.FIELDS || fields.stream().anyMatch(f -> f.name().equals(field)))
                continue;
            var options = texts(firstPresent(item, "options", "values", "choices"), 40).stream().distinct().limit(Blueprint.OPTIONS).toList();
            var kind = KINDS.getOrDefault(Objects.toString(item.get("kind") == null ? item.get("type") : item.get("kind"), "string")
                    .strip().toLowerCase(Locale.ROOT), Blueprint.Kind.STRING);
            if (!options.isEmpty() && kind == Blueprint.Kind.STRING)
                kind = Blueprint.Kind.ENUM;
            if (kind == Blueprint.Kind.ENUM && options.isEmpty())
                kind = Blueprint.Kind.STRING;
            String fieldName = field.substring(0, Math.min(40, field.length()));
            fields.add(new Blueprint.Field(fieldName, kind, kind != Blueprint.Kind.IMAGE && Boolean.TRUE.equals(item.get("required")),
                    kind == Blueprint.Kind.ENUM ? options : List.of(), kind == Blueprint.Kind.ENUM ? tones(options, item.get("tones")) : Map.of()));
        }
        String requested = Blueprint.lower(identifierWords(text(raw.get("display"), 80, "")));
        var display = fields.stream().filter(field -> field.name().equals(requested) && field.textual()).findFirst()
                .or(() -> fields.stream().filter(field -> field.kind() == Blueprint.Kind.STRING).findFirst())
                .or(() -> fields.stream().filter(field -> field.kind() == Blueprint.Kind.TEXT).findFirst()).orElse(null);
        if (display == null) {
            if (fields.size() == Blueprint.FIELDS)
                fields.removeLast();
            display = new Blueprint.Field(fields.stream().anyMatch(field -> field.name().equals("name")) ? "title" : "name",
                    Blueprint.Kind.STRING, true, List.of(), Map.of());
            fields.addFirst(display);
        } else if (!display.required()) {
            int index = fields.indexOf(display);
            display = new Blueprint.Field(display.name(), display.kind(), true, List.of(), Map.of());
            fields.set(index, display);
        }
        String shown = display.name();
        var search = new ArrayList<String>();
        for (String field : texts(raw.get("search") instanceof String single ? List.of(single) : raw.get("search"), 80)) {
            String candidate = Blueprint.lower(identifierWords(field));
            if (fields.stream().anyMatch(f -> f.name().equals(candidate) && f.textual()) && !search.contains(candidate))
                search.add(candidate);
        }
        if (search.isEmpty())
            search.add(shown);
        String wanted = Blueprint.lower(identifierWords(text(raw.get("filter"), 80, "")));
        String filter = fields.stream().filter(field -> field.name().equals(wanted)
                        && (field.kind() == Blueprint.Kind.ENUM || field.kind() == Blueprint.Kind.BOOLEAN)).map(Blueprint.Field::name)
                .findFirst().orElseGet(() -> fields.stream().filter(field -> field.kind() == Blueprint.Kind.ENUM)
                        .map(Blueprint.Field::name).findFirst().orElse(null));
        String icon = Blueprint.ICONS.contains(text(raw.get("icon"), 40, "")) ? text(raw.get("icon"), 40, "") : iconFor(name + " " + plural, "Layers");
        long facts = fields.stream().filter(field -> !field.textual() && field.kind() != Blueprint.Kind.IMAGE).count();
        String layout = Blueprint.LAYOUTS.contains(text(raw.get("layout"), 20, "")) ? text(raw.get("layout"), 20, "")
                : facts >= 4 && fields.stream().noneMatch(field -> field.kind() == Blueprint.Kind.IMAGE) ? "table" : "grid";
        return new Blueprint.Entity(name, plural, icon, layout, access(text(raw.get("access"), 40, "owner")), fields, shown, search, filter);
    }

    private static List<Blueprint.Page> pages(List<?> raw, List<Blueprint.Entity> entities, List<Blueprint.Feature> features,
            List<String> later) {
        var proposed = new ArrayList<Blueprint.Page>();
        var detailed = new LinkedHashSet<String>();
        for (var value : raw) {
            var item = map(value);
            String path = path(text(item.get("path"), 160, null));
            var entity = entityFor(text(item.get("entity"), 80, null), entities);
            var kind = PAGE_KINDS.getOrDefault(Objects.toString(item.get("kind"), "custom").strip().toLowerCase(Locale.ROOT),
                    Blueprint.PageKind.CUSTOM);
            if (kind.generated() && entity == null) {
                if (item.get("entity") != null) {
                    later.add(text(item.get("title"), 60, Objects.toString(item.get("entity"))) + " page");
                    continue;
                }
                kind = Blueprint.PageKind.CUSTOM;
            }
            if (kind == Blueprint.PageKind.DETAIL) {
                detailed.add(entity.name());
                continue;
            }
            if (kind == Blueprint.PageKind.LIST && proposed.stream().anyMatch(page -> page.kind() == Blueprint.PageKind.LIST
                    && page.entity().equals(entity.name())))
                kind = Blueprint.PageKind.CUSTOM;
            else if (kind == Blueprint.PageKind.LIST && (path == null || path.contains(":")))
                path = "/" + Blueprint.kebab(entity.plural());
            if (path == null || ApprovedProductScope.signInRoute(path) || path.equals(Blueprint.ACCOUNT_PATH))
                continue;
            String fallback = kind == Blueprint.PageKind.LIST ? entity.pluralLabel()
                    : path.equals("/") ? "Home" : Blueprint.words(identifierWords(path));
            String wanted = slug(text(item.get("feature"), 40, null));
            String feature = wanted != null && features.stream().anyMatch(f -> f.id().equals(wanted)) ? wanted : null;
            proposed.add(new Blueprint.Page(path, text(item.get("title"), 60, fallback.isBlank() ? "Page" : fallback), kind,
                    entity == null ? null : entity.name(), feature, text(item.get("description"), 160, null)));
        }
        if (proposed.stream().noneMatch(page -> page.path().equals("/"))) {
            var landing = proposed.stream().filter(page -> page.kind() == Blueprint.PageKind.LANDING).findFirst();
            if (landing.isPresent()) {
                var page = landing.get();
                proposed.set(proposed.indexOf(page), new Blueprint.Page("/", page.title(), page.kind(), page.entity(), page.feature(), page.description()));
            } else
                proposed.addFirst(new Blueprint.Page("/", "Home", Blueprint.PageKind.LANDING, null, null, null));
        }
        proposed.sort(Comparator.comparing(page -> !page.path().equals("/")));
        var pages = new ArrayList<Blueprint.Page>();
        var paths = new HashSet<String>();
        int agent = 0;
        for (var page : proposed) {
            if (!paths.add(page.path()) || !page.kind().generated() && agent == Blueprint.AGENT_PAGES) {
                later.add(page.title() + " page");
                continue;
            }
            if (!page.kind().generated())
                agent++;
            pages.add(page);
        }
        for (var page : List.copyOf(pages))
            if (page.kind() == Blueprint.PageKind.LIST)
                detailed.add(page.entity());
        for (String name : detailed) {
            var entity = entities.stream().filter(e -> e.name().equals(name)).findFirst().orElseThrow();
            String list = pages.stream().filter(page -> page.kind() == Blueprint.PageKind.LIST && page.entity().equals(name))
                    .map(Blueprint.Page::path).findFirst().orElse(null);
            String detail = Blueprint.detailPath(list, entity);
            if (paths.add(detail))
                pages.add(new Blueprint.Page(detail, entity.label(), Blueprint.PageKind.DETAIL, entity.name(), null, null));
        }
        return pages;
    }

    private static Blueprint.Entity entityFor(String text, List<Blueprint.Entity> entities) {
        if (text == null)
            return null;
        String wanted = identifierWords(text).toLowerCase(Locale.ROOT);
        return entities.stream().filter(entity -> entity.name().toLowerCase(Locale.ROOT).equals(wanted)
                || entity.plural().toLowerCase(Locale.ROOT).equals(wanted)).findFirst().orElse(null);
    }

    private static final List<Map.Entry<String, String>> ICON_HINTS = List.of(
            Map.entry("task|todo|chore|checklist", "ListTodo"), Map.entry("habit|plant|garden", "Sprout"),
            Map.entry("note|memo", "StickyNote"), Map.entry("deal|opportunit", "Handshake"),
            Map.entry("company|organi[sz]ation|business|vendor|supplier|firm", "Building2"),
            Map.entry("invoice|bill|payment|expense|transaction", "Receipt"), Map.entry("budget|wallet|saving|finance", "Wallet"),
            Map.entry("order|cart|purchase", "ShoppingCart"), Map.entry("product|item|inventory|stock|sku", "Package"),
            Map.entry("event|meeting|appointment|booking|schedule|session|calendar", "CalendarDays"),
            Map.entry("score|leaderboard|rank|award|achievement|trophy", "Trophy"), Map.entry("level|maze|map|world|stage|quest", "Map"),
            Map.entry("game|play", "Gamepad2"), Map.entry("post|article|blog|news|story", "Newspaper"),
            Map.entry("comment|message|chat|review|feedback", "MessageSquare"), Map.entry("recipe|meal|food|dish|menu|kitchen", "ChefHat"),
            Map.entry("workout|exercise|fitness|gym", "Dumbbell"), Map.entry("book|course|lesson|class|quiz", "BookOpen"),
            Map.entry("project|campaign|goal|okr|milestone", "Target"), Map.entry("ticket|issue|bug|incident|support", "Ticket"),
            Map.entry("file|document|report|doc", "FileText"), Map.entry("photo|image|gallery|album", "Image"),
            Map.entry("song|music|track|playlist", "Music"), Map.entry("trip|travel|flight", "Plane"),
            Map.entry("pet|animal|dog|cat", "PawPrint"), Map.entry("job|position|hire|candidate|career", "Briefcase"),
            Map.entry("store|shop|seller|market", "Store"), Map.entry("shipment|delivery|shipping", "Truck"),
            Map.entry("video|movie|film", "Film"), Map.entry("category|tag|label", "Tag"), Map.entry("activit|log|history", "Activity"),
            Map.entry("team|group|club", "Users"),
            Map.entry("contact|customer|client|lead|person|people|member|user|player|student|patient|employee|profile", "User"));
    private static final List<Map.Entry<String, String>> TONE_HINTS = List.of(
            Map.entry("done|complete|completed|won|active|published|paid|approved|easy|resolved|delivered|healthy|success|live|yes|in stock", "success"),
            Map.entry("pending|in progress|in-progress|medium|draft|review|waiting|scheduled|on hold|normal|processing|trial|low stock", "warning"),
            Map.entry("lost|cancell?ed|failed|hard|overdue|blocked|urgent|high|critical|rejected|expired|out of stock|refunded", "danger"),
            Map.entry("new|todo|to do|backlog|low|archived|inactive|closed|paused|none|no", "neutral"));

    static String iconFor(String text, String fallback) {
        String words = String.join(" ", text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+"));
        for (var hint : ICON_HINTS)
            if (Pattern.compile("\\b(?:" + hint.getKey() + ")").matcher(words).find())
                return hint.getValue();
        return fallback;
    }

    private static Map<String, String> tones(List<String> options, Object proposed) {
        var tones = new LinkedHashMap<String, String>();
        var given = map(proposed);
        for (String option : options) {
            String wanted = text(given.get(option), 20, "").toLowerCase(Locale.ROOT);
            if (Blueprint.TONES.contains(wanted)) {
                tones.put(option, wanted);
                continue;
            }
            String plain = option.toLowerCase(Locale.ROOT).replace('_', ' ').strip();
            tones.put(option, TONE_HINTS.stream().filter(hint -> plain.matches("(?:" + hint.getKey() + ")")).map(Map.Entry::getValue)
                    .findFirst().orElse("accent"));
        }
        return tones;
    }

    private static Blueprint.Access access(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
        case "shared", "public", "community", "everyone", "social" -> Blueprint.Access.SHARED;
        case "admin", "catalog", "catalogue", "global", "managed", "readonly", "read-only" -> Blueprint.Access.ADMIN;
        default -> Blueprint.Access.OWNER;
        };
    }

    private static String path(String text) {
        if (text == null)
            return null;
        String value = text.strip().replace('\\', '/').replaceAll("\\s+", "-").replaceAll("[^A-Za-z0-9_/:.-]", "")
                .replaceAll("/{2,}", "/").replace("..", "");
        if (!value.startsWith("/"))
            value = "/" + value;
        if (value.length() > 1 && value.endsWith("/"))
            value = value.substring(0, value.length() - 1);
        return value.length() <= 121 && value.matches("/[A-Za-z0-9_/:.-]{0,120}") ? value : null;
    }

    private static String pluralOf(String singular) {
        if (singular.matches(".*[^aeiou]y"))
            return singular.substring(0, singular.length() - 1) + "ies";
        if (singular.matches(".*(?:s|x|z|ch|sh)"))
            return singular + "es";
        return singular + "s";
    }

    private static String identifierWords(String text) {
        if (text == null)
            return "";
        var words = text.strip().replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+");
        var out = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty())
                continue;
            String part = word.toLowerCase(Locale.ROOT);
            out.append(out.isEmpty() ? part : Blueprint.upper(part));
        }
        String value = out.toString().replaceFirst("^[0-9]+", "");
        return value;
    }

    private static String slug(String text) {
        if (text == null)
            return null;
        String value = text.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "")
                .replaceFirst("^[0-9-]+", "");
        value = value.substring(0, Math.min(40, value.length())).replaceAll("-+$", "");
        return value.isEmpty() ? null : value;
    }

    private static String color(Object value) {
        if (!(value instanceof String text))
            return null;
        String hex = text.strip();
        if (hex.matches("#[0-9a-fA-F]{3}"))
            hex = "#" + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2) + hex.charAt(3) + hex.charAt(3);
        return hex.matches("#[0-9a-fA-F]{6}") ? hex.toLowerCase(Locale.ROOT) : null;
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys)
            if (map.get(key) != null)
                return map.get(key);
        return null;
    }

    private static String text(Object value, int max, String fallback) {
        if (!(value instanceof String text) || text.isBlank())
            return fallback;
        String clean = text.strip().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max).strip();
    }

    private static List<String> texts(Object value, int max) {
        return list(value).stream().map(item -> text(item, max, null)).filter(Objects::nonNull).toList();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items.stream().filter(Objects::nonNull).toList() : List.of();
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Map<String, Blueprint.Kind> kinds() {
        var kinds = new HashMap<String, Blueprint.Kind>();
        for (String alias : List.of("string", "str", "short", "title", "name", "email", "url", "link", "phone", "color"))
            kinds.put(alias, Blueprint.Kind.STRING);
        for (String alias : List.of("text", "longtext", "long_text", "textarea", "description", "markdown", "richtext", "notes"))
            kinds.put(alias, Blueprint.Kind.TEXT);
        for (String alias : List.of("number", "int", "integer", "float", "double", "decimal", "price", "money", "currency", "rating", "score", "count"))
            kinds.put(alias, Blueprint.Kind.NUMBER);
        for (String alias : List.of("boolean", "bool", "checkbox", "toggle", "flag", "yesno"))
            kinds.put(alias, Blueprint.Kind.BOOLEAN);
        for (String alias : List.of("date", "datetime", "timestamp", "time", "day"))
            kinds.put(alias, Blueprint.Kind.DATE);
        for (String alias : List.of("image", "photo", "picture", "avatar", "thumbnail", "cover", "logo", "image_url", "imageurl"))
            kinds.put(alias, Blueprint.Kind.IMAGE);
        for (String alias : List.of("enum", "select", "choice", "status", "options", "category", "tag"))
            kinds.put(alias, Blueprint.Kind.ENUM);
        return Map.copyOf(kinds);
    }

    private static Map<String, Blueprint.PageKind> pageKinds() {
        var kinds = new HashMap<String, Blueprint.PageKind>();
        for (String alias : List.of("landing", "home", "hero", "marketing", "welcome"))
            kinds.put(alias, Blueprint.PageKind.LANDING);
        for (String alias : List.of("dashboard", "overview", "stats", "analytics", "summary"))
            kinds.put(alias, Blueprint.PageKind.DASHBOARD);
        for (String alias : List.of("list", "collection", "browse", "catalog", "table", "index", "crud"))
            kinds.put(alias, Blueprint.PageKind.LIST);
        for (String alias : List.of("detail", "details", "view", "show", "record"))
            kinds.put(alias, Blueprint.PageKind.DETAIL);
        for (String alias : List.of("settings", "preferences", "profile", "account"))
            kinds.put(alias, Blueprint.PageKind.SETTINGS);
        return Map.copyOf(kinds);
    }
}
