package com.code.pal.agent.contract;

import java.util.*;

public record Blueprint(String name, String preset, String accent, List<Entity> entities, List<Page> pages,
        List<Feature> features, List<String> later) {
    public static final int ENTITIES = 6;
    public static final int AGENT_PAGES = 5;
    public static final int FIELDS = 16;
    public static final int OPTIONS = 12;
    public static final int FEATURES = 8;
    public static final int LATER = 12;
    static final Set<String> RESERVED_FIELDS = Set.of("id", "_id", "accountId", "createdAt", "updatedAt", "__v");
    static final Set<String> RESERVED_PLURALS = Set.of("api", "auth", "entities", "types", "models", "crud", "routes");

    public enum Kind {
        STRING, TEXT, NUMBER, BOOLEAN, DATE, ENUM
    }

    public enum Access {
        OWNER, SHARED, ADMIN
    }

    public enum PageKind {
        LANDING, DASHBOARD, LIST, DETAIL, SETTINGS, CUSTOM;

        public boolean generated() {
            return this == LIST || this == DETAIL;
        }
    }

    public record Field(String name, Kind kind, boolean required, List<String> options) {
        public Field {
            BuildContract.identifier(name);
            if (RESERVED_FIELDS.contains(name))
                bad("Reserved field name " + name);
            Objects.requireNonNull(kind);
            options = options == null ? List.of() : List.copyOf(options);
            if ((kind == Kind.ENUM) == options.isEmpty() || options.size() > OPTIONS
                    || options.stream().anyMatch(option -> option.isBlank() || option.length() > 40)
                    || new HashSet<>(options).size() != options.size())
                bad("Invalid options for field " + name);
        }

        public boolean textual() {
            return kind == Kind.STRING || kind == Kind.TEXT;
        }
    }

    public record Entity(String name, String plural, Access access, List<Field> fields, String display,
            List<String> search, String filter) {
        public Entity {
            if (name == null || !name.matches("[A-Z][A-Za-z0-9]{0,39}"))
                bad("Invalid entity name");
            if (plural == null || !plural.matches("[a-z][A-Za-z0-9]{0,39}") || RESERVED_PLURALS.contains(plural))
                bad("Invalid plural for " + name);
            Objects.requireNonNull(access);
            fields = List.copyOf(fields);
            search = List.copyOf(search);
            if (fields.isEmpty() || fields.size() > FIELDS)
                bad("Entity " + name + " needs 1 to " + FIELDS + " fields");
            var byName = new HashMap<String, Field>();
            fields.forEach(field -> {
                if (byName.put(field.name(), field) != null)
                    bad("Duplicate field " + field.name());
            });
            var shown = byName.get(display);
            if (shown == null || !shown.textual() || !shown.required())
                bad("Display field of " + name + " must be a required text field");
            if (search.stream().anyMatch(field -> byName.get(field) == null || !byName.get(field).textual()))
                bad("Search fields of " + name + " must be text fields");
            if (filter != null && (byName.get(filter) == null
                    || !Set.of(Kind.ENUM, Kind.BOOLEAN).contains(byName.get(filter).kind())))
                bad("Filter field of " + name + " must be an enum or yes/no field");
        }

        public Field field(String fieldName) {
            return fields.stream().filter(field -> field.name().equals(fieldName)).findFirst().orElse(null);
        }

        public String label() {
            return words(name);
        }

        public String pluralLabel() {
            return words(plural);
        }

        public String apiPath() {
            return "/api/" + kebab(plural);
        }

        public String variable() {
            return lower(name);
        }
    }

    public record Page(String path, String title, PageKind kind, String entity, String feature, String description) {
        public Page {
            if (path == null || !path.matches("/[A-Za-z0-9_/:.-]{0,120}") || path.contains("..") || path.contains("//"))
                bad("Invalid page path");
            if (title == null || title.isBlank() || title.length() > 60)
                bad("Invalid page title");
            Objects.requireNonNull(kind);
            if (kind.generated() && entity == null)
                bad("Generated page " + path + " needs an entity");
            if (kind == PageKind.LIST && path.contains(":"))
                bad("List page " + path + " cannot take route parameters");
            if (description != null && (description.isBlank() || description.length() > 160))
                bad("Invalid page description");
        }
    }

    public record Feature(String id, String description) {
        public Feature {
            if (id == null || !id.matches("[a-z][a-z0-9-]{0,39}"))
                bad("Invalid feature id");
            if (description == null || description.isBlank() || description.length() > 600)
                bad("Invalid feature description");
        }
    }

    public Blueprint {
        if (name == null || name.isBlank() || name.length() > 60)
            bad("Invalid app name");
        if (!DesignPresets.NAMES.contains(preset))
            bad("Unknown design preset");
        if (accent != null && !accent.matches("#[0-9a-fA-F]{6}"))
            bad("Invalid accent colour");
        entities = List.copyOf(entities);
        pages = List.copyOf(pages);
        features = List.copyOf(features);
        later = List.copyOf(later);
        if (entities.size() > ENTITIES || features.size() > FEATURES || later.size() > LATER
                || later.stream().anyMatch(item -> item.isBlank() || item.length() > 160))
            bad("Blueprint exceeds bounds");
        var names = new HashSet<String>();
        var plurals = new HashSet<String>();
        for (var entity : entities)
            if (!names.add(entity.name()) || !plurals.add(entity.plural()))
                bad("Duplicate entity " + entity.name());
        var featureIds = new HashSet<String>();
        features.forEach(feature -> {
            if (!featureIds.add(feature.id()))
                bad("Duplicate feature " + feature.id());
        });
        var paths = new HashSet<String>();
        var generated = new HashSet<String>();
        int agentPages = 0;
        boolean home = false;
        for (var page : pages) {
            if (!paths.add(page.path()))
                bad("Duplicate page path " + page.path());
            if (ApprovedProductScope.signInRoute(page.path()))
                bad("Sign-in pages are platform-owned");
            if (page.entity() != null && !names.contains(page.entity()))
                bad("Page " + page.path() + " uses unknown entity " + page.entity());
            if (page.feature() != null && !featureIds.contains(page.feature()))
                bad("Page " + page.path() + " uses unknown feature " + page.feature());
            if (page.kind().generated() && !generated.add(page.kind() + " " + page.entity()))
                bad("Entity " + page.entity() + " has two " + page.kind() + " pages");
            if (!page.kind().generated())
                agentPages++;
            home |= page.path().equals("/");
        }
        if (!home || agentPages > AGENT_PAGES)
            bad("Blueprint needs a / page and at most " + AGENT_PAGES + " written pages");
        for (var page : pages) {
            if (!page.kind().generated())
                continue;
            var entity = entities.stream().filter(e -> e.name().equals(page.entity())).findFirst().orElseThrow();
            var list = pages.stream().filter(p -> p.kind() == PageKind.LIST && entity.name().equals(p.entity())).findFirst();
            String detail = detailPath(list.map(Page::path).orElse(null), entity);
            if (page.kind() == PageKind.DETAIL && !page.path().equals(detail))
                bad("Detail page of " + entity.name() + " must be " + detail);
            if (page.kind() == PageKind.LIST && pages.stream().noneMatch(p -> p.path().equals(detail)))
                bad("List page " + page.path() + " needs a page at " + detail);
        }
    }

    public Entity entity(String entityName) {
        return entities.stream().filter(entity -> entity.name().equals(entityName)).findFirst().orElse(null);
    }

    public Page page(String entityName, PageKind kind) {
        return pages.stream().filter(page -> page.kind() == kind && entityName.equals(page.entity())).findFirst().orElse(null);
    }

    public String listPath(Entity entity) {
        var list = page(entity.name(), PageKind.LIST);
        return list == null ? null : list.path();
    }

    public String detailPath(Entity entity) {
        return detailPath(listPath(entity), entity);
    }

    static String detailPath(String listPath, Entity entity) {
        return (listPath == null || listPath.equals("/") ? "/" + kebab(entity.plural()) : listPath) + "/:id";
    }

    public Feature feature(String id) {
        return features.stream().filter(feature -> feature.id().equals(id)).findFirst().orElse(null);
    }

    static String lower(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    static String upper(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String kebab(String identifier) {
        return identifier.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT);
    }

    static String words(String identifier) {
        String text = identifier.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT).strip();
        return upper(text);
    }

    private static void bad(String reason) {
        throw new IllegalArgumentException(reason);
    }
}
