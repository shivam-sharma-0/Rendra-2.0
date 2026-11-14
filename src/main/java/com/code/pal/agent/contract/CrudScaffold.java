package com.code.pal.agent.contract;

import com.code.pal.project.capability.BuildDecisions;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.function.Function;

final class CrudScaffold {
    private final ObjectMapper mapper;

    CrudScaffold(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    static boolean generated(BuildContract c, String path) {
        return c.blueprint() != null && c.units().stream()
                .filter(unit -> c.blueprint().entities().stream().anyMatch(entity -> unit.id().equals(BlueprintCompiler.crudUnit(entity))))
                .anyMatch(unit -> unit.ownedPaths().contains(path));
    }

    Map<String, String> render(BuildContract c, UUID projectId, Function<String, String> existing) {
        var out = new LinkedHashMap<String, String>();
        var blueprint = c.blueprint();
        if (blueprint == null)
            return out;
        boolean mern = c.stack() == BuildContract.Stack.MERN;
        String root = c.clientRoot(), kit = (mern ? "client/src/" : "src/") + "kit/index.ts";
        out.put(root + "crud.tsx", ContractScaffold.resource("contract/crud.tsx").replace("{{kit}}", ContractScaffold.relative(root, kit, true)));
        out.put(root + "entities.ts", clientEntities(c, projectId));
        if (!mern)
            out.put(root + "local-store.ts", ContractScaffold.resource("contract/local-store.ts"));
        if (mern) {
            out.put(c.serverRoot() + "crud.ts", ContractScaffold.resource("contract/crud-server.ts"));
            out.put(c.serverRoot() + "entities.ts", serverEntities(blueprint));
        }
        for (var entity : blueprint.entities()) {
            if (mern)
                fresh(out, existing, BlueprintCompiler.routerPath(entity), router(c, entity));
            for (var route : c.routes()) {
                if (!route.owner().equals(BlueprintCompiler.crudUnit(entity)))
                    continue;
                String directory = route.componentPath().substring(0, route.componentPath().lastIndexOf('/') + 1);
                String imports = "import { " + (route.componentPath().endsWith("ListPage.tsx") ? "RecordList" : "RecordDetail")
                        + " } from " + json(ContractScaffold.relative(directory, root + "crud.tsx", true)) + "\nimport { "
                        + entity.plural() + " } from " + json(ContractScaffold.relative(directory, root + "entities.ts", true)) + "\n\n";
                var page = blueprint.pages().stream().filter(p -> p.path().equals(route.path())).findFirst().orElseThrow();
                String component = route.componentPath().substring(directory.length(), route.componentPath().length() - 4);
                fresh(out, existing, route.componentPath(), imports + (page.kind() == Blueprint.PageKind.LIST
                        ? "export default function " + component + "() {\n  return <RecordList spec={" + entity.plural() + "} title={"
                                + json(page.title()) + "}" + (page.description() == null ? "" : " description={" + json(page.description()) + "}")
                                + " />\n}\n"
                        : "export default function " + component + "({ params }: { params: Record<string, string> }) {\n  return <RecordDetail spec={"
                                + entity.plural() + "} id={params.id ?? ''} />\n}\n"));
            }
        }
        return out;
    }

    String models(BuildContract c) {
        boolean accounts = c.auth() == BuildDecisions.Auth.ACCOUNTS;
        var b = new StringBuilder("import mongoose from 'mongoose'\n");
        if (accounts)
            b.append("import { ownedSchema, ownedModel, sharedSchema, sharedModel } from './ownership.js'\n");
        b.append("export const models = {\n");
        for (var entity : c.blueprint().entities()) {
            String owned = accounts ? entity.access() == Blueprint.Access.OWNER ? "owned" : "shared" : null;
            String name = json("Rendra_" + entity.name());
            b.append(entity.name()).append(": ").append(owned == null ? "" : owned + "Model(").append("mongoose.models[").append(name)
                    .append("] ?? mongoose.model(").append(name).append(", ").append(owned == null ? "new mongoose.Schema" : owned + "Schema").append("({\n");
            for (var field : entity.fields()) {
                b.append(json(field.name())).append(": { type: ").append(switch (field.kind()) {
                case NUMBER -> "Number";
                case BOOLEAN -> "Boolean";
                default -> "String";
                }).append(", required: ").append(field.required());
                if (field.kind() == Blueprint.Kind.ENUM)
                    b.append(", enum: ").append(mapper.writeValueAsString(field.options()));
                if (field.textual())
                    b.append(", maxlength: ").append(field.kind() == Blueprint.Kind.TEXT ? 5000 : 200);
                b.append(" },\n");
            }
            if (owned == null)
                b.append("}, { timestamps: true, toJSON: { virtuals: true }, toObject: { virtuals: true } })),\n");
            else
                b.append(owned.equals("shared") ? "}, " + json(entity.access() == Blueprint.Access.ADMIN ? "admin" : "shared") + "))),\n" : "}))),\n");
        }
        return b.append("}\n").toString();
    }

    private String router(BuildContract c, Blueprint.Entity entity) {
        String directory = "server/src/routers/", server = c.serverRoot();
        boolean admin = c.auth() == BuildDecisions.Auth.ACCOUNTS && entity.access() == Blueprint.Access.ADMIN;
        return "import { crudRouter } from " + json(ContractScaffold.relative(directory, server + "crud.ts", false)) + "\n"
                + (admin ? "import { requireAdmin } from " + json(ContractScaffold.relative(directory, server + "auth.ts", false)) + "\n" : "")
                + "import { entities } from " + json(ContractScaffold.relative(directory, server + "entities.ts", false)) + "\n"
                + "import { models } from " + json(ContractScaffold.relative(directory, server + "models.ts", false)) + "\n\n"
                + "export const " + entity.plural() + "Router = crudRouter(models." + entity.name() + ", entities." + entity.name()
                + (admin ? ", requireAdmin" : "") + ")\n";
    }

    private String serverEntities(Blueprint blueprint) {
        var b = new StringBuilder("import type { EntitySpec } from './crud.js'\n\nexport const entities: Record<string, EntitySpec> = {\n");
        for (var entity : blueprint.entities()) {
            b.append("  ").append(entity.name()).append(": {\n    display: ").append(json(entity.display()))
                    .append(",\n    search: ").append(mapper.writeValueAsString(entity.search()))
                    .append(entity.filter() == null ? "" : ",\n    filter: " + json(entity.filter()))
                    .append(",\n    fields: [\n");
            for (var field : entity.fields())
                b.append("      { name: ").append(json(field.name())).append(", kind: ").append(json(kind(field)))
                        .append(", required: ").append(field.required())
                        .append(field.options().isEmpty() ? "" : ", options: " + mapper.writeValueAsString(field.options())).append(" },\n");
            b.append("    ],\n  },\n");
        }
        return b.append("}\n").toString();
    }

    private String clientEntities(BuildContract c, UUID projectId) {
        var blueprint = c.blueprint();
        boolean mern = c.stack() == BuildContract.Stack.MERN, accounts = c.auth() == BuildDecisions.Auth.ACCOUNTS;
        var b = new StringBuilder("import type * as Types from './types'\nimport type { EntitySpec } from './crud'\n");
        b.append(mern ? "import { api } from './api'\n" : "import { localStore } from './local-store'\n");
        if (accounts)
            b.append("import { auth } from './auth-client'\n");
        for (var entity : blueprint.entities()) {
            String type = "Types." + entity.name(), input = "Types." + entity.name() + "Input";
            String list = blueprint.listPath(entity), detail = blueprint.detailPath(entity);
            b.append("\nexport const ").append(entity.plural()).append(": EntitySpec<").append(type).append(", ").append(input).append("> = {\n")
                    .append("  label: ").append(json(entity.label())).append(",\n")
                    .append("  pluralLabel: ").append(json(entity.pluralLabel())).append(",\n")
                    .append("  plural: ").append(json(entity.plural())).append(",\n")
                    .append("  recordPath: ").append(json(detail.substring(0, detail.length() - "/:id".length()))).append(",\n");
            if (list != null)
                b.append("  listPath: ").append(json(list)).append(",\n");
            b.append("  display: ").append(json(entity.display())).append(",\n  fields: [\n");
            for (var field : entity.fields())
                b.append("    { name: ").append(json(field.name())).append(", label: ").append(json(Blueprint.words(field.name())))
                        .append(", kind: ").append(json(kind(field))).append(", required: ").append(field.required())
                        .append(field.options().isEmpty() ? "" : ", options: " + mapper.writeValueAsString(field.options())).append(" },\n");
            b.append("  ],\n  search: ").append(!entity.search().isEmpty()).append(",\n");
            if (entity.filter() != null)
                b.append("  filter: ").append(json(entity.filter())).append(",\n");
            String create = !accounts || entity.access() != Blueprint.Access.ADMIN ? "() => true" : "() => auth.isAdmin()";
            String edit = !accounts || entity.access() == Blueprint.Access.OWNER ? "() => true"
                    : entity.access() == Blueprint.Access.ADMIN ? "() => auth.isAdmin()" : "record => record.accountId === auth.getSession()?.userId";
            b.append("  canCreate: ").append(create).append(",\n  canEdit: ").append(edit).append(",\n");
            if (mern) {
                String plural = Blueprint.upper(entity.plural()), name = entity.name();
                b.append("  source: {\n")
                        .append("    list: query => api.list").append(plural).append("({}, undefined, undefined, query),\n")
                        .append("    get: id => api.get").append(name).append("({ id }),\n")
                        .append("    create: input => api.create").append(name).append("({}, input),\n")
                        .append("    update: (id, input) => api.update").append(name).append("({ id }, input),\n")
                        .append("    remove: id => api.delete").append(name).append("({ id }),\n")
                        .append("  },\n");
            } else
                b.append("  source: localStore<").append(type).append(", ").append(input).append(">(")
                        .append(json("rendra-data:" + projectId + ":" + entity.plural())).append(", ")
                        .append(mapper.writeValueAsString(entity.search()))
                        .append(entity.filter() == null ? "" : ", " + json(entity.filter())).append("),\n");
            b.append("}\n");
        }
        return b.toString();
    }

    private static String kind(Blueprint.Field field) {
        return field.kind().name().toLowerCase(Locale.ROOT);
    }

    private static void fresh(Map<String, String> out, Function<String, String> existing, String path, String content) {
        if (existing.apply(path) == null)
            out.put(path, content);
    }

    private String json(String text) {
        return mapper.writeValueAsString(text);
    }
}
