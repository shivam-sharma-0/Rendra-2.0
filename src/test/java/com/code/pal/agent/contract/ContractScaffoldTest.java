package com.code.pal.agent.contract;
import com.code.pal.agent.starter.Starter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractScaffoldTest {
    final ContractScaffold scaffold=new ContractScaffold(new ObjectMapper());
    @Test void accountClientUsesStableProjectScopeAndLeavesPasswordsOutOfSessionState() throws Exception {
        var mapper = new ObjectMapper();
        var contract = ContractFixtures.accounts();
        UUID project = UUID.randomUUID();
        var files = scaffold.render(contract, "Fixture", path -> null, project);
        String client = files.get(contract.clientRoot() + "auth-client.ts");
        assertNotNull(client);
        Map<String, Object> changed = mapper.readValue(mapper.writeValueAsString(contract), Map.class);
        changed.put("id", UUID.randomUUID().toString());
        var next = mapper.convertValue(changed, BuildContract.class);
        assertEquals(client, scaffold.render(next, "Fixture", path -> null, project).get(next.clientRoot() + "auth-client.ts"));
        var other = scaffold.render(contract, "Fixture", path -> null, UUID.randomUUID());
        assertNotEquals(client, other.get(contract.clientRoot() + "auth-client.ts"));
        Path evidence = Path.of("target/codex-contract-scaffold/client-auth.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, mapper.writeValueAsString(Map.of("auth", client,
                "api", files.get(contract.clientRoot() + "api.ts"),
                "otherProjectAuth", other.get(contract.clientRoot() + "auth-client.ts"),
                "getMethod", contract.api().stream().filter(api -> api.method().equals("GET")).findFirst().orElseThrow().id())));
    }
    @Test void patchBodiesArePartialButPutAndPostKeepTheFullRequestType() {
        // Live E2E 2026-10-28: Home toggled completion with PATCH { completed } and TaskInput demanded every field.
        var mapper=new ObjectMapper();
        var patched=mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()).replace("\"PUT\"","\"PATCH\""),BuildContract.class);
        String patchApi=scaffold.render(patched,"Fixture",path->null).get(patched.clientRoot()+"api.ts");
        assertTrue(patchApi.contains("itemPUT: (params: Record<string,string> = {}, body: Partial<Types.ItemInput>"),patchApi);
        assertTrue(patchApi.contains("itemPOST: (params: Record<string,string> = {}, body: Types.ItemInput,"),patchApi);
        var put=ContractFixtures.mern();
        assertTrue(scaffold.render(put,"Fixture",path->null).get(put.clientRoot()+"api.ts").contains("itemPUT: (params: Record<string,string> = {}, body: Types.ItemInput,"));
    }
    @Test void accountsAlwaysInstallOwnerContextAndOwnerBoundModels() {
        var c=ContractFixtures.accounts();var files=scaffold.render(c,"Fixture",path->null);
        assertTrue(files.get(c.serverRoot()+"register.ts").contains("requireAccount, bindRecordOwner, itemsRouter"));
        assertTrue(files.get(c.serverRoot()+"models.ts").contains("ownedModel("));assertTrue(files.get(c.serverRoot()+"models.ts").contains("ownedSchema("));assertTrue(files.containsKey(c.serverRoot()+"ownership.ts"));
    }
    @Test void freshDeclaredPageIsWiredToTheRootWithoutAnotherGenerationUnit(){
        var c=ContractFixtures.vite();var page=new BuildContract.Unit("ui","Build page",List.of("src/Items.tsx"),List.of(),List.of(),List.of("ui"));
        var routed=new BuildContract(c.schemaVersion(),c.id(),c.stack(),c.storage(),c.auth(),c.limits(),c.theme(),List.of(new BuildContract.Route("/","src/Items.tsx","ui")),c.types(),c.models(),c.api(),c.assets(),c.envNames(),List.of(page),c.acceptance());
        var output=scaffold.render(routed,"Fixture",path->null);String root=output.get("src/App.tsx");assertTrue(root.contains("import { routes }"));assertTrue(root.contains("<Component params={params} />"));assertFalse(root.contains("PendingApp"));assertTrue(root.contains("theme.css"));
        assertFalse(scaffold.render(routed,"Fixture",path->path.equals("src/App.tsx")?"existing root":null).containsKey("src/App.tsx"));
    }
    @Test void deterministicNewStacksHaveOneThemeMatchingSharedInterfacesAndPendingRouters() throws Exception {
        for(var c:List.of(ContractFixtures.staticSite(),ContractFixtures.vite(),ContractFixtures.mern())){
            var first=scaffold.render(c,"Fixture",p->null);assertEquals(first,scaffold.render(c,"Fixture",p->null));
            assertTrue(first.get(c.clientRoot()+"theme.css").contains("--rendra-accent: #123456"));
            // The design system reads --app-*: the approved palette must reach it (2026-10-29 review).
            assertTrue(first.get(c.clientRoot()+"theme.css").contains("--app-accent: var(--rendra-accent);"));
            assertEquals(c.theme().containsKey("foreground"),first.get(c.clientRoot()+"theme.css").contains("--app-text: var(--rendra-foreground);"));
            if(c.stack()==BuildContract.Stack.MERN){
                assertEquals(first.get(c.clientRoot()+"types.ts"),first.get(c.serverRoot()+"types.ts"));
                assertTrue(first.get(c.serverRoot()+"register.ts").contains("app.use(\"/api/items\", itemsRouter)"));
                assertTrue(first.get("server/src/items.ts").contains("status(501)"));
            }
            // Disposable exported inputs for the pinned package runner; production/customer files excluded.
            Path base=Path.of("target/codex-contract-scaffold",c.stack().name().toLowerCase(Locale.ROOT)).toAbsolutePath();
            for(var e:first.entrySet()){var target=base.resolve(e.getKey()).normalize();assertTrue(target.startsWith(base));Files.createDirectories(target.getParent());Files.writeString(target,e.getValue());}
            if(c.stack()==BuildContract.Stack.MERN){
                for(String helper:List.of("auth.ts","ownership.ts"))try(var in=getClass().getClassLoader().getResourceAsStream("contract/"+helper)){var target=base.resolve(c.serverRoot()+helper);Files.write(target,in.readAllBytes());}
            }
        }
    }
    @Test void existingAppThemeBootstrapAndDependenciesArePreserved() {
        var c=ContractFixtures.vite();var existing=Map.of("package.json","existing deps","src/App.tsx","existing app","src/main.tsx","existing main","src/index.css","existing css");
        var output=scaffold.render(c,"Fixture",existing::get);
        for(var path:existing.keySet())assertFalse(output.containsKey(path),path);
        var mern=ContractFixtures.mern();var old=Starter.MERN.files("Old");var server=old.get("server/src/index.ts")+"\n// existing custom logic\n";
        output=scaffold.render(mern,"Feature",p->p.equals("server/src/index.ts")?server:old.get(p));
        assertTrue(output.get("server/src/index.ts").contains("// existing custom logic"));assertFalse(output.containsKey("client/package.json"));
        var rendered=output.get("server/src/index.ts");var repeated=scaffold.render(mern,"Feature",p->p.equals("server/src/index.ts")?rendered:old.get(p));assertEquals(rendered,repeated.get("server/src/index.ts"));
        assertThrows(IllegalArgumentException.class,()->scaffold.render(mern,"Feature",p->p.equals("server/src/index.ts")?"unknown bootstrap":old.get(p)));
    }
    @Test void onlyApprovedSupabaseChoiceEmitsThePublicConnectorHook() {
        var c=ContractFixtures.vite();
        assertFalse(scaffold.render(c,"Fixture",p->null).containsKey(c.clientRoot()+"supabase.ts"));
        var connected=new BuildContract(c.schemaVersion(),c.id(),c.stack(),com.code.pal.project.capability.BuildDecisions.Storage.SUPABASE,c.auth(),c.limits(),c.theme(),c.routes(),c.types(),c.models(),c.api(),c.assets(),Set.of("VITE_SUPABASE_URL","VITE_SUPABASE_ANON_KEY"),c.units(),c.acceptance());
        var output=scaffold.render(connected,"Fixture",p->p.equals("package.json")?"existing":null);
        assertTrue(output.get(c.clientRoot()+"supabase.ts").contains("import.meta.env.VITE_SUPABASE_URL"));
        assertFalse(output.containsKey("package.json"));
        assertFalse(output.values().stream().anyMatch(text->text.contains("service_role") || text.contains("createClient(")));
    }
}
