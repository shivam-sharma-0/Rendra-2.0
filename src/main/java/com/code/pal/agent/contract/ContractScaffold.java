package com.code.pal.agent.contract;

import com.code.pal.agent.starter.Starter;
import com.code.pal.project.capability.BuildDecisions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

/** Pure rendering. The coordinator owns all mutations; no LLM, installation or connector activation here. */
@Component
public final class ContractScaffold {
    private final ObjectMapper mapper;
    public ContractScaffold(ObjectMapper mapper){this.mapper=mapper;}
    public Map<String,String> render(BuildContract c,String title,Function<String,String> existing) {
        var out=new LinkedHashMap<String,String>();
        if(c.stack()!=BuildContract.Stack.STATIC) {
            String manifest=c.stack()==BuildContract.Stack.MERN?"client/package.json":"package.json";
            if(existing.apply(manifest)==null)out.putAll((c.stack()==BuildContract.Stack.MERN?Starter.MERN:Starter.VITE_REACT).files(title));
        }
        String root=c.clientRoot();
        var theme=new StringBuilder(":root {\n");new TreeMap<>(c.theme()).forEach((k,v)->theme.append("  --rendra-").append(k).append(": ").append(k.equals("font")?switch(v){case "system"->"system-ui, sans-serif";case "mono"->"monospace";default->"serif";}:v).append(";\n"));
        out.put(root+"theme.css",theme.append("}\n").toString());
        out.put(root+"contract.json",mapper.writeValueAsString(Map.of("version",c.schemaVersion(),"id",c.id(),"envNames",new TreeSet<>(c.envNames()))));
        if(c.stack()==BuildContract.Stack.STATIC){out.put(root+"routes.json",mapper.writeValueAsString(c.routes()));return Collections.unmodifiableMap(out);}
        String types="export type ApiError = { error: string }\nexport type Credentials = { email: string; password: string }\nexport type AuthSession = { token: string; userId: string }\n";
        for(var type:c.types()){if(Set.of("ApiError","Credentials","AuthSession").contains(type.name()))throw new IllegalArgumentException("Built-in type name is reserved");types+=type(type);}
        out.put(root+"types.ts",types);out.put(root+"api.ts",api(c));
        StringBuilder routes=new StringBuilder("import type { ComponentType } from 'react'\n");
        for(int i=0;i<c.routes().size();i++){var route=c.routes().get(i);routes.append("import Route").append(i).append(" from ").append(json(relative(root,route.componentPath(),true))).append("\n");
            if(existing.apply(route.componentPath())==null)out.put(route.componentPath(),"export default function PendingRoute() { return null }\n");}
        routes.append("export const routes: {path: string; component: ComponentType}[] = [\n");
        for(int i=0;i<c.routes().size();i++)routes.append("{path:").append(json(c.routes().get(i).path())).append(",component: Route").append(i).append("},\n");
        out.put(root+"routes.ts",routes.append("]\n").toString());
        String app=c.stack()==BuildContract.Stack.MERN?"client/src/App.tsx":"src/App.tsx";
        if(existing.apply(app)==null && !c.ownership().containsKey(app) && !c.routes().isEmpty()) {
            String directory=app.substring(0,app.lastIndexOf('/')+1);
            out.put(app,"import "+json(relative(directory,root+"theme.css",true))+"\nimport { routes } from "+json(relative(directory,root+"routes.ts",true))+"\nexport default function App() {\nconst route = routes.find(route => route.path === window.location.pathname) ?? routes.find(route => route.path === '/') ?? routes[0]\nconst Component = route?.component\nreturn Component ? <Component /> : null\n}\n");
        }
        if(existing.apply(app)==null && !out.containsKey(app))out.put(app,"export default function PendingApp() { return null }\n");
        if(c.storage()==BuildDecisions.Storage.SUPABASE)out.put(root+"supabase.ts",SUPABASE);
        if(c.stack()==BuildContract.Stack.MERN) {
            out.put(c.serverRoot()+"types.ts",types);out.put(c.serverRoot()+"models.ts",models(c));
            var registration=new StringBuilder("import type { Express } from 'express'\n");
            if(c.auth()==BuildDecisions.Auth.ACCOUNTS){out.put(c.serverRoot()+"auth.ts",resource("contract/auth.ts"));out.put(c.serverRoot()+"ownership.ts",resource("contract/ownership.ts"));registration.append("import { authRouter, requireAccount } from './auth.js'\nimport { bindRecordOwner } from './ownership.js'\n");}
            var routers=new TreeMap<String,BuildContract.Api>();c.api().forEach(a->routers.putIfAbsent(a.routerPath(),a));
            for(var endpoint:routers.values()){
                registration.append("import { ").append(endpoint.routerExport()).append(" } from ").append(json(relative(c.serverRoot(),endpoint.routerPath(),false))).append("\n");
                if(existing.apply(endpoint.routerPath())==null)out.put(endpoint.routerPath(),"import { Router } from 'express'\nexport const "+endpoint.routerExport()+" = Router()\n"+endpoint.routerExport()+".use((_req,res) => { res.status(501).json({error:'Implementation pending'}) })\n");
            }
            registration.append("export function registerContract(app: Express) {\n");
            if(c.auth()==BuildDecisions.Auth.ACCOUNTS)registration.append("app.use('/api/auth', authRouter)\n");
            for(var endpoint:routers.values())registration.append("app.use(").append(json(endpoint.basePath())).append(c.auth()==BuildDecisions.Auth.ACCOUNTS?", requireAccount, bindRecordOwner":"").append(", ").append(endpoint.routerExport()).append(")\n");
            out.put(c.serverRoot()+"register.ts",registration.append("}\n").toString());
            String entry=existing.apply("server/src/index.ts");if(entry==null)entry=out.get("server/src/index.ts");
            String marker="// Register the app's routers here, every one under /api, e.g. app.use('/api/todos', todos).";
            if(entry==null || !entry.contains(marker))throw new IllegalArgumentException("Existing server needs a reviewed router insertion point; bootstrap is preserved");
            String symbol="register_"+c.id().toString().replace("-","");
            String statement="import { registerContract as "+symbol+" } from './rendra/"+c.id()+"/register.js'\n";
            if(!entry.contains(statement))entry=statement+entry.replace(marker,marker+"\n"+symbol+"(app)\n");
            out.put("server/src/index.ts",entry);
        }
        return Collections.unmodifiableMap(out);
    }
    private String type(BuildContract.DataType t){StringBuilder b=new StringBuilder("export interface "+t.name()+" {\n");for(var f:t.fields())b.append(json(f.name())).append(f.optional()?"?: ":": ").append(switch(f.kind()){case STRING->"string";case NUMBER->"number";case BOOLEAN->"boolean";case JSON->"unknown";case STRING_ARRAY->"string[]";case NUMBER_ARRAY->"number[]";case JSON_ARRAY->"unknown[]";}).append("\n");return b.append("}\n").toString();}
    private String models(BuildContract c) {
        boolean owned=c.auth()==BuildDecisions.Auth.ACCOUNTS;
        var b=new StringBuilder("import mongoose from 'mongoose'\n");
        if(owned)b.append("import { ownedSchema, ownedModel } from './ownership.js'\n");
        b.append("export const models = {\n");
        for(var t:c.models()) {
            b.append(t.name()).append(": ");if(owned)b.append("ownedModel(");
            b.append("mongoose.models[").append(json("Rendra_"+t.name())).append("] ?? mongoose.model(").append(json("Rendra_"+t.name())).append(owned?", ownedSchema({\n":", new mongoose.Schema({\n");
            for(var f:t.fields())if(!owned || !f.name().equals("accountId"))b.append(json(f.name())).append(": { type: ").append(switch(f.kind()){case STRING->"String";case NUMBER->"Number";case BOOLEAN->"Boolean";case JSON->"mongoose.Schema.Types.Mixed";case STRING_ARRAY->"[String]";case NUMBER_ARRAY->"[Number]";case JSON_ARRAY->"[mongoose.Schema.Types.Mixed]";}).append(", required: ").append(!f.optional()).append(" },\n");
            b.append(owned?"}))),\n":"}, { timestamps: true, toJSON: { virtuals: true }, toObject: { virtuals: true } })),\n");
        }
        return b.append("}\n").toString();
    }
    private String api(BuildContract c){if(c.api().isEmpty())return "export {}\n";var b=new StringBuilder("import type * as Types from './types'\n");b.append("async function request<T>(path: string, method: string, body: unknown, token?: string): Promise<T> {\nconst response = await fetch(path, {method, headers: {'Content-Type':'application/json', ...(token ? {Authorization: `Bearer ${token}`} : {})}, ...(body === undefined ? {} : {body: JSON.stringify(body)})});\nif (!response.ok) throw new Error(`API request failed (${response.status})`);\nreturn (response.status === 204 ? undefined : await response.json()) as T\n}\nexport const api = {\n");for(var a:c.api()){b.append(a.id()).append(": (params: Record<string,string>, body: ").append(a.requestType()==null?"undefined":ref(a.requestType())).append(", token?: string) => request<").append(ref(a.responseType())).append(">(");b.append(json(a.path())).append(".replace(/:([A-Za-z][A-Za-z0-9_]*)/g, (_, key: string) => {if (!params[key]) throw new Error('Missing API parameter'); return encodeURIComponent(params[key])}), ").append(json(a.method())).append(", body, token),\n");}return b.append("}\n").toString();}
    private String ref(String type){return type.equals("unknown")?"unknown":"Types."+type;}
    private String json(String text){return mapper.writeValueAsString(text);}
    private static String relative(String directory,String target,boolean client){String s=Path.of(directory).relativize(Path.of(target)).toString().replace('\\','/');s=s.replaceFirst("\\.(?:tsx?|jsx?)$",client?"":".js");return s.startsWith(".")?s:"./"+s;}
    private static String resource(String path){try(var in=ContractScaffold.class.getClassLoader().getResourceAsStream(path)){if(in==null)throw new IllegalStateException("Missing coordinator resource");return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    private static final String SUPABASE="""
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
