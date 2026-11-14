package com.code.pal.agent.contract;

import com.code.pal.agent.validation.AccountAcceptance;

import com.code.pal.agent.starter.Starter;
import com.code.pal.project.capability.BuildDecisions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Pure rendering. The coordinator owns all mutations; no LLM, installation or connector activation here. */
@Component
public final class ContractScaffold {
    /** Contract theme key → design-system token it drives (muted is the planner's quiet line colour, not text). */
    private static final Map<String, String> DESIGN_TOKENS = Map.of("background", "background", "foreground", "text",
            "accent", "accent", "muted", "border", "radius", "radius", "spacing", "space", "font", "font");
    private final ObjectMapper mapper;

    public ContractScaffold(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> render(BuildContract c, String title, Function<String, String> existing) {
        return render(c, title, existing, c.id());
    }

    static void derivedTokens(Map<String, String> theme, StringBuilder css) {
        int[] background = DesignPresets.rgb(theme.get("background"));
        if (background == null)
            return;
        boolean dark = DesignPresets.luminance(background) < 0.4;
        int[] text = DesignPresets.rgb(theme.get("foreground"));
        if (text == null || DesignPresets.contrast(background, text) < 4.5) {
            text = dark ? new int[] {0xf8, 0xfa, 0xfc} : new int[] {0x11, 0x18, 0x27};
            css.append("  --app-text: ").append(DesignPresets.hex(text)).append(";\n");
        }
        int[] surface = dark ? DesignPresets.mix(background, text, 0.07)
                : DesignPresets.mix(background, new int[] {255, 255, 255}, 0.75);
        css.append("  color-scheme: ").append(dark ? "dark" : "light").append(";\n");
        css.append("  --app-surface: ").append(DesignPresets.hex(surface)).append(";\n");
        css.append("  --app-muted: ").append(DesignPresets.hex(DesignPresets.mix(text, surface, 0.32))).append(";\n");
        int[] border = DesignPresets.rgb(theme.get("muted"));
        if (border == null || DesignPresets.contrast(surface, border) < 1.3)
            css.append("  --app-border: ").append(DesignPresets.hex(DesignPresets.mix(surface, text, dark ? 0.2 : 0.14))).append(";\n");
        int[] accent = DesignPresets.rgb(theme.get("accent"));
        if (accent != null)
            css.append("  --app-on-accent: ").append(DesignPresets.contrast(accent, new int[] {255, 255, 255})
                    >= DesignPresets.contrast(accent, new int[] {17, 24, 39}) ? "#ffffff" : "#111827").append(";\n");
    }

    @SuppressWarnings("unchecked")
    static boolean platformPage(BuildContract c, String componentPath) {
        return c.stack() == BuildContract.Stack.MERN && c.auth() == BuildDecisions.Auth.ACCOUNTS
                && c.routes().stream().anyMatch(route -> route.componentPath().equals(componentPath)
                        && ApprovedProductScope.signInRoute(route.path()));
    }

    public Map<String, String> render(BuildContract c, String title, Function<String, String> existing, UUID projectId) {
        var out = new LinkedHashMap<String, String>();
        if (c.schemaVersion() == 2 && c.stack() == BuildContract.Stack.STATIC) {
            var starter = StaticAssets.files(c.id());
            for (String name : List.of("package.json", "package-lock.json")) {
                String old = existing.apply(name);
                if (old != null && !mapper.readTree(old).equals(mapper.readTree(starter.get(name)))) {
                    throw new IllegalArgumentException("Static starter cannot replace user-owned dependency manifests");
                }
            }
            out.putAll(starter);
        }
        if (c.stack() != BuildContract.Stack.STATIC) {
            String manifest = c.stack() == BuildContract.Stack.MERN ? "client/package.json" : "package.json";
            if (existing.apply(manifest) == null)
                out.putAll((c.stack() == BuildContract.Stack.MERN ? Starter.MERN : Starter.VITE_REACT).files(title));
            if (c.schemaVersion() == 2 && out.containsKey("package.json")) {
                Map<String, Object> manifestData = mapper.readValue(out.get("package.json"), Map.class);
                Map<String, Object> scripts = (Map<String, Object>) manifestData.get("scripts");
                scripts.put("build", "vite build");
                out.put("package.json", mapper.writeValueAsString(manifestData));
            }
        }
        String root = c.clientRoot();
        var theme = new StringBuilder(":root {\n");
        if (c.theme().containsKey("preset")) {
            DesignPresets.css(c.theme().get("preset"), c.theme().get("accent"), theme);
        } else {
            new TreeMap<>(c.theme()).forEach(
                    (k, v) -> theme.append("  --rendra-").append(k).append(": ").append(k.equals("font") ? switch (v) {
                    case "system" -> "system-ui, sans-serif";
                    case "mono" -> "monospace";
                    default -> "serif";
                    } : v).append(";\n"));
            // The design system reads --app-*: the approved palette must reach it, not only pages that ask for --rendra-*.
            new TreeMap<>(DESIGN_TOKENS).forEach((key, token) -> {
                if (c.theme().containsKey(key))
                    theme.append("  --app-").append(token).append(": var(--rendra-").append(key).append(");\n");
            });
            derivedTokens(c.theme(), theme);
        }
        out.put(root + "theme.css", theme.append("}\n").toString());
        out.put(root + "contract.json", mapper.writeValueAsString(
                Map.of("version", c.schemaVersion(), "id", c.id(), "envNames", new TreeSet<>(c.envNames()))));
        if (c.stack() == BuildContract.Stack.STATIC) {
            out.put(root + "routes.json", mapper.writeValueAsString(c.routes()));
            return Collections.unmodifiableMap(out);
        }
        String types = "export type ApiError = { error: string }\nexport type Credentials = { email: string; password: string }\nexport type AuthSession = { token: string; userId: string }\n";
        for (var type : c.types()) {
            if (BuildContract.BUILT_IN_TYPES.contains(type.name()))
                throw new IllegalArgumentException("Built-in type name is reserved");
            types += type(type);
        }
        out.put(root + "types.ts", types);
        if (c.stack() == BuildContract.Stack.MERN && c.auth() == BuildDecisions.Auth.ACCOUNTS)
            out.put(root + "auth-client.ts", resource("contract/auth-client.ts")
                    .replace("{{storageKey}}", json(AccountAcceptance.storageKey(projectId))));
        out.put(root + "api.ts", api(c));
        boolean accounts = c.stack() == BuildContract.Stack.MERN && c.auth() == BuildDecisions.Auth.ACCOUNTS;
        if (accounts)
            out.put(root + "AccountPage.tsx", resource("contract/AccountPage.tsx"));
        StringBuilder routes = new StringBuilder("import type { ComponentType } from 'react'\n");
        for (int i = 0; i < c.routes().size(); i++) {
            var route = c.routes().get(i);
            routes.append("import Route").append(i).append(" from ")
                    .append(json(relative(root, route.componentPath(), true))).append("\n");
            if (accounts && platformPage(c, route.componentPath()))
                out.put(route.componentPath(), "export { default } from " + json(relative(
                        route.componentPath().substring(0, route.componentPath().lastIndexOf('/') + 1), root + "AccountPage", true)) + "\n");
            else if (existing.apply(route.componentPath()) == null)
                out.put(route.componentPath(), "export default function PendingRoute() { return null }\n");
        }
        routes.append("export const routes: {path: string; component: ComponentType<{params: Record<string,string>}>}[] = [\n");
        for (int i = 0; i < c.routes().size(); i++)
            routes.append("{path:").append(json(c.routes().get(i).path())).append(",component: Route").append(i)
                    .append("},\n");
        out.put(root + "routes.ts", routes.append("]\n").toString());
        String app = c.stack() == BuildContract.Stack.MERN ? "client/src/App.tsx" : "src/App.tsx";
        if (existing.apply(app) == null && !c.ownership().containsKey(app) && !c.routes().isEmpty()) {
            String directory = app.substring(0, app.lastIndexOf('/') + 1);
            String signIn = c.routes().stream().map(BuildContract.Route::path).filter(ApprovedProductScope::signInRoute)
                    .filter(path -> !path.contains(":")).findFirst().orElse("/login");
            String accountMenu = accounts
                    ? "import { auth, useSession } from " + json(relative(directory, root + "auth-client", true)) + "\n"
                            + resource("contract/account-menu.tsx").replace("{{signIn}}", "#" + signIn)
                    : "function AccountMenu() { return null }\n";
            out.put(app, "import " + json(relative(directory, root + "theme.css", true)) + "\nimport { routes } from "
                    + json(relative(directory, root + "routes.ts", true))
                    + "\nconst APP_NAME = " + json(title == null || title.isBlank() ? "App" : title.strip())
                    + "\n" + resource("contract/hash-app.tsx") + accountMenu);
        }
        if (existing.apply(app) == null && !out.containsKey(app))
            out.put(app, "export default function PendingApp() { return null }\n");
        if (c.storage() == BuildDecisions.Storage.SUPABASE)
            out.put(root + "supabase.ts", SUPABASE);
        if (c.stack() == BuildContract.Stack.MERN) {
            out.put(c.serverRoot() + "types.ts", types);
            out.put(c.serverRoot() + "models.ts", models(c));
            var registration = new StringBuilder("import type { Express } from 'express'\n");
            if (c.auth() == BuildDecisions.Auth.ACCOUNTS) {
                out.put(c.serverRoot() + "auth.ts", resource("contract/auth.ts"));
                out.put(c.serverRoot() + "ownership.ts", resource("contract/ownership.ts"));
                registration.append(
                        "import { authRouter, requireAccount } from './auth.js'\nimport { bindRecordOwner } from './ownership.js'\n");
            }
            var routers = new TreeMap<String, BuildContract.Api>();
            c.api().forEach(a -> routers.putIfAbsent(a.routerPath(), a));
            for (var endpoint : routers.values()) {
                registration.append("import { ").append(endpoint.routerExport()).append(" } from ")
                        .append(json(relative(c.serverRoot(), endpoint.routerPath(), false))).append("\n");
                if (existing.apply(endpoint.routerPath()) == null)
                    out.put(endpoint.routerPath(), "import { Router } from 'express'\nexport const "
                            + endpoint.routerExport() + " = Router()\n" + (endpoint.basePath().equals("/api") ? ""
                                    : endpoint.routerExport() + ".use((_req,res) => { res.status(501).json({error:'Implementation pending'}) })\n"));
            }
            registration.append("export function registerContract(app: Express) {\n");
            if (c.auth() == BuildDecisions.Auth.ACCOUNTS)
                registration.append("app.use('/api/auth', authRouter)\n");
            for (var endpoint : routers.values())
                registration.append("app.use(").append(json(endpoint.basePath()))
                        .append(c.auth() == BuildDecisions.Auth.ACCOUNTS ? ", requireAccount, bindRecordOwner" : "")
                        .append(", ").append(endpoint.routerExport()).append(")\n");
            out.put(c.serverRoot() + "register.ts", registration.append("}\n").toString());
            String entry = existing.apply("server/src/index.ts");
            if (entry == null)
                entry = out.get("server/src/index.ts");
            String marker = "// Register the app's routers here, every one under /api, e.g. app.use('/api/todos', todos).";
            if (entry == null || !entry.contains(marker))
                throw new IllegalArgumentException(
                        "Existing server needs a reviewed router insertion point; bootstrap is preserved");
            String symbol = "register_" + c.id().toString().replace("-", "");
            String statement = "import { registerContract as " + symbol + " } from './rendra/" + c.id()
                    + "/register.js'\n";
            if (!entry.contains(statement))
                entry = statement + entry.replace(marker, marker + "\n" + symbol + "(app)\n");
            out.put("server/src/index.ts", entry);
        }
        return Collections.unmodifiableMap(out);
    }

    public static String revision() {
        return ContractStore.hash("contract-scaffold-2026-11-14.2/" + Starter.TEMPLATE_VERSION + "/" + StaticAssets.revision()
                + "/" + resource("contract/hash-app.tsx"));
    }

    private String type(BuildContract.DataType t) {
        StringBuilder b = new StringBuilder("export interface " + t.name() + " {\n");
        for (var f : t.fields())
            b.append(json(f.name())).append(f.optional() ? "?: " : ": ").append(switch (f.kind()) {
            case STRING -> "string";
            case NUMBER -> "number";
            case BOOLEAN -> "boolean";
            case JSON -> "unknown";
            case STRING_ARRAY -> "string[]";
            case NUMBER_ARRAY -> "number[]";
            case JSON_ARRAY -> "unknown[]";
            }).append("\n");
        return b.append("}\n").toString();
    }

    private String models(BuildContract c) {
        boolean owned = c.auth() == BuildDecisions.Auth.ACCOUNTS;
        var b = new StringBuilder("import mongoose from 'mongoose'\n");
        if (owned)
            b.append("import { ownedSchema, ownedModel } from './ownership.js'\n");
        b.append("export const models = {\n");
        for (var t : c.models()) {
            b.append(t.name()).append(": ");
            if (owned)
                b.append("ownedModel(");
            b.append("mongoose.models[").append(json("Rendra_" + t.name())).append("] ?? mongoose.model(")
                    .append(json("Rendra_" + t.name()))
                    .append(owned ? ", ownedSchema({\n" : ", new mongoose.Schema({\n");
            for (var f : t.fields())
                if (!owned || !f.name().equals("accountId"))
                    b.append(json(f.name())).append(": { type: ").append(switch (f.kind()) {
                    case STRING -> "String";
                    case NUMBER -> "Number";
                    case BOOLEAN -> "Boolean";
                    case JSON -> "mongoose.Schema.Types.Mixed";
                    case STRING_ARRAY -> "[String]";
                    case NUMBER_ARRAY -> "[Number]";
                    case JSON_ARRAY -> "[mongoose.Schema.Types.Mixed]";
                    }).append(", required: ").append(!f.optional()).append(" },\n");
            b.append(owned ? "}))),\n"
                    : "}, { timestamps: true, toJSON: { virtuals: true }, toObject: { virtuals: true } })),\n");
        }
        return b.append("}\n").toString();
    }

    private String api(BuildContract c) {
        if (c.api().isEmpty())
            return "export {}\n";
        var b = new StringBuilder("import type * as Types from './types'\n");
        boolean accounts = c.stack() == BuildContract.Stack.MERN && c.auth() == BuildDecisions.Auth.ACCOUNTS;
        if (accounts) b.append("import { auth } from './auth-client'\n");
        b.append(resource("contract/api-request.ts"));
        b.append(accounts ? "\nconst request = createRequest(auth)\n" : "\nconst request = createRequest()\n");
        b.append("export const api = {\n");
        for (var a : c.api()) {
            b.append(a.id()).append(": (params: Record<string,string> = {}, ")
                    // PATCH is a partial update: a page that toggles one field must not have to resend the record.
                    .append(a.requestType() == null ? "body?: undefined" : "body: " + (a.method().equals("PATCH")
                            && !a.requestType().endsWith("[]") ? "Partial<" + ref(a.requestType()) + ">" : ref(a.requestType())))
                    .append(", token?: string) => request<").append(ref(a.responseType())).append(">(");
            b.append(json(a.path())).append(
                    ".replace(/:([A-Za-z][A-Za-z0-9_]*)/g, (_, key: string) => {if (!params[key]) throw new Error('Missing API parameter'); return encodeURIComponent(params[key])}), ")
                    .append(json(a.method())).append(", body, token),\n");
        }
        return b.append("}\n").toString();
    }

    private String ref(String type) {
        return type.equals("unknown") ? "unknown" : "Types." + type;
    }

    private String json(String text) {
        return mapper.writeValueAsString(text);
    }

    private static String relative(String directory, String target, boolean client) {
        String s = Path.of(directory).relativize(Path.of(target)).toString().replace('\\', '/');
        s = s.replaceFirst("\\.(?:tsx?|jsx?)$", client ? "" : ".js");
        return s.startsWith(".") ? s : "./" + s;
    }

    private static String resource(String path) {
        try (var in = ContractScaffold.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null)
                throw new IllegalStateException("Missing coordinator resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final String SUPABASE = """
            // The approved connector supplies public build variables; no SDK or secret is invented.
            export async function supabaseRequest<T>(path: string, method: string, body?: unknown, accessToken?: string): Promise<T> {
              const url = import.meta.env.VITE_SUPABASE_URL
              const key = import.meta.env.VITE_SUPABASE_ANON_KEY
              if (!url || !key) throw new Error('Approved Supabase connection unavailable')
              if (!/^\\/[A-Za-z0-9_/?=&.%:-]*$/.test(path) || path.includes('..') || path.startsWith('//')) throw new Error('Invalid connector path')
              const response = await fetch(`${url}/rest/v1${path}`, {method, headers: {apikey: key, Authorization: `Bearer ${accessToken ?? key}`, 'Content-Type':'application/json'}, ...(body === undefined ? {} : {body: JSON.stringify(body)})})
              if (!response.ok) throw new Error(`Storage request failed (${response.status})`)
              return (response.status === 204 ? undefined : await response.json()) as T
            }
            """;
}
