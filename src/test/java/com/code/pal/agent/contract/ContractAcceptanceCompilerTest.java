package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractAcceptanceCompilerTest {
    final ObjectMapper mapper=new ObjectMapper();

    @Test void missingRouteValuesUseOnlyAnExplicitDeclaredTargetOrTheImmediateLink() {
        for (String missing : Arrays.asList(null, "", "  ")) {
            var data = routeProposal();
            var action = new LinkedHashMap<String, Object>();
            action.put("id", "ui_login_success"); action.put("kind", "ASSERT_ROUTE");
            action.put("selector", "/"); action.put("value", missing);
            ((Map<String, Object>) data.get("acceptance")).put("browser", List.of(action));
            var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(
                    mapper.writeValueAsString(data), mapper), BuildContract.class);
            assertEquals("/", compiled.acceptance().browser().getFirst().value());
        }
        var data = routeProposal();
        ((Map<String, Object>) data.get("acceptance")).put("browser", List.of(
                Map.of("id", "open", "kind", "CLICK", "selector", "a[href='#/tasks/123']"),
                Map.of("id", "route", "kind", "ASSERT_ROUTE", "value", "")));
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper), BuildContract.class);
        assertEquals("/tasks/123", compiled.acceptance().browser().getLast().value());
    }

    @SuppressWarnings("unchecked")
    @Test void aBareApiRouterIsMountedAtItsSingleResourceButMixedOrTakenBasesAreKept() {
        // Live E2E 2026-10-27: basePath /api mounted a router written as '/' and '/:id', so GET /api/todos hit '/:id'.
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()), Map.class);
        var api = (List<Map<String, Object>>) data.get("api");
        api.forEach(entry -> entry.put("basePath", "/api"));
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data), mapper), BuildContract.class);
        assertEquals(Set.of("/api/items"), compiled.api().stream().map(BuildContract.Api::basePath).collect(java.util.stream.Collectors.toSet()));
        assertEquals(ContractFixtures.mern().api().stream().map(BuildContract.Api::path).toList(), compiled.api().stream().map(BuildContract.Api::path).toList());

        api.getFirst().put("path", "/api/things");
        Map<String, Object> mixed = mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data), mapper), Map.class);
        assertTrue(((List<Map<String, Object>>) mixed.get("api")).stream().allMatch(entry -> "/api".equals(entry.get("basePath"))));

        api.getFirst().put("path", "/api/items");
        var other = new LinkedHashMap<>(api.getFirst());
        other.putAll(Map.of("id", "otherList", "path", "/api/items", "routerPath", "server/src/other.ts", "basePath", "/api/items"));
        api.add(other);
        Map<String, Object> taken = mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data), mapper), Map.class);
        assertEquals(4, ((List<Map<String, Object>>) taken.get("api")).stream().filter(entry -> "/api".equals(entry.get("basePath"))).count());
    }

    @Test void ambiguousRedirectsExternalLinksAndInterveningActionsNeverGuessARoute() {
        var data = routeProposal();
        for (String selector : List.of("button[data-action='login']", "a[href='/login'],a[href='/signup']",
                "a[href='https://external.example.invalid/login']", "a[href='/unknown']")) {
            ((Map<String, Object>) data.get("acceptance")).put("browser", List.of(
                    Map.of("id", "login", "kind", "CLICK", "selector", selector),
                    Map.of("id", "ui_login_success", "kind", "ASSERT_ROUTE", "value", "")));
            assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                    mapper.writeValueAsString(data), mapper));
        }
        ((Map<String, Object>) data.get("acceptance")).put("browser", List.of(
                Map.of("id", "login", "kind", "CLICK", "selector", "a[href='/login']"),
                Map.of("id", "conflict", "kind", "ASSERT_ROUTE", "selector", "/signup", "value", "")));
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper));
        ((Map<String, Object>) data.get("acceptance")).put("browser", List.of(
                Map.of("id", "open", "kind", "CLICK", "selector", "a[href='/login']"),
                Map.of("id", "fill", "kind", "FILL", "selector", "input[name='email']", "value", "fixture@example.invalid"),
                Map.of("id", "route", "kind", "ASSERT_ROUTE", "value", "")));
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper));
    }

    private Map<String, Object> routeProposal() {
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()), Map.class);
        var routes = (List<Map<String, Object>>) data.get("routes");
        for (String path : List.of("/login", "/signup", "/tasks/:id"))
            routes.add(Map.of("path", path, "componentPath", "client/src/App.tsx", "owner", "ui"));
        ((List<Map<String, Object>>) data.get("units")).getLast().put("acceptanceIds", List.of());
        return data;
    }

    @Test void optionalPreferencesTrimWithoutDroppingRoutesOrAnyAccountCrudEvidence() {
        var data = preferenceProposal();
        var originalApi = mapper.writeValueAsString(((Map<?, ?>) data.get("acceptance")).get("api"));
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper, "Build task CRUD with settings navigation"), BuildContract.class);
        assertEquals(23, compiled.acceptance().browser().size());
        assertTrue(compiled.acceptance().browser().stream().anyMatch(action -> action.id().equals("settings_route")));
        assertEquals(originalApi, mapper.writeValueAsString(compiled.acceptance().api()));
        assertTrue(compiled.acceptance().authRequired());
        assertTrue(compiled.acceptance().crudRequired());
        assertFalse(compiled.units().stream().flatMap(unit -> unit.acceptanceIds().stream())
                .anyMatch(id -> id.equals("pref_save")));
    }

    @Test void requiredPreferencesAndUnknownScopeAreNeverAssumedOptional() {
        String proposal = mapper.writeValueAsString(preferenceProposal());
        for (String scope : Arrays.asList(null, "Persist the selected theme after reload",
                "{\"requestedScope\":{\"features\":[{\"description\":\"Save user preferences\"}]}}"))
            assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(proposal, mapper, scope));
    }

    @Test void designPaletteDoesNotMakeOptionalPreferencesRequiredButApprovedFeaturesDo() {
        String proposal = mapper.writeValueAsString(preferenceProposal());
        String paletteOnly = "{\"requestedScope\":{\"theme\":{\"accent\":\"#123456\"},\"features\":[]}}";
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(
                proposal, mapper, "Build task CRUD with settings navigation", paletteOnly), BuildContract.class);
        assertEquals(23, compiled.acceptance().browser().size());
        String required = "{\"requestedScope\":{\"theme\":{},\"features\":[{\"description\":\"Persist theme choice\"}]}}";
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                proposal, mapper, "Build tasks", required));
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                proposal, mapper, "Build tasks", "{\"theme\":{\"preference\":\"Persist selected mode\"}}"));
    }

    @Test void optionalChecksCannotHideRequiredOverflowOrUnknownSettingActions() {
        var data = preferenceProposal();
        var browser = (List<Map<String, Object>>) ((Map<?, ?>) data.get("acceptance")).get("browser");
        browser.add(Map.of("id", "required_search", "kind", "FILL", "selector", "input[name='search']", "value", "task"));
        browser.add(Map.of("id", "required_filter", "kind", "CLICK", "selector", "button[data-filter='active']"));
        String requiredOverflow = mapper.writeValueAsString(data);
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                requiredOverflow, mapper, "Search and filter tasks"));

        data = preferenceProposal();
        browser = (List<Map<String, Object>>) ((Map<?, ?>) data.get("acceptance")).get("browser");
        browser.get(23).put("selector", "button[data-action='delete-account']");
        browser.get(24).put("selector", "button[data-action='unknown']");
        String unknownActions = mapper.writeValueAsString(data);
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                unknownActions, mapper, "Build tasks with settings navigation"));
    }

    @Test void duplicateAssertionsAreRemovedOnlyWithoutAnInterveningStateChange() {
        var data = preferenceProposal();
        var acceptance = (Map<String, Object>) data.get("acceptance");
        var browser = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 25; i++)
            browser.add(new LinkedHashMap<>(Map.of("id", i == 0 ? "ui_created" : "duplicate_" + i,
                    "kind", "ASSERT_TEXT", "selector", "[data-task]", "value", "Created task")));
        acceptance.put("browser", browser);
        ((List<Map<String, Object>>) data.get("units")).getLast().put("acceptanceIds",
                browser.stream().map(action -> action.get("id")).toList());
        var compiled = mapper.readValue(ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper), BuildContract.class);
        assertEquals(1, compiled.acceptance().browser().size());
        assertEquals("ui_created", compiled.acceptance().browser().getFirst().id());

        browser.get(1).putAll(Map.of("kind", "FILL", "selector", "input[name='title']", "value", "Edited task"));
        for (int i = 3; i < browser.size(); i++) browser.get(i).put("value", "Required evidence " + i);
        assertThrows(RuntimeException.class, () -> ContractAcceptanceCompiler.completeCollections(
                mapper.writeValueAsString(data), mapper));
    }

    private Map<String, Object> preferenceProposal() {
        var original = ContractFixtures.accounts();
        Map<String, Object> data = mapper.readValue(mapper.writeValueAsString(original), Map.class);
        var browser = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 22; i++)
            browser.add(new LinkedHashMap<>(Map.of("id", "required_" + i, "kind", "ASSERT_TEXT",
                    "selector", "#required-" + i, "value", "Required evidence " + i)));
        browser.add(new LinkedHashMap<>(Map.of("id", "settings_route", "kind", "ASSERT_ROUTE", "value", "/settings")));
        browser.add(new LinkedHashMap<>(Map.of("id", "pref_theme", "kind", "SELECT", "selector", "select[name='theme']", "value", "dark")));
        browser.add(new LinkedHashMap<>(Map.of("id", "pref_save", "kind", "CLICK", "selector", "button[data-action='save-preferences']")));
        browser.add(new LinkedHashMap<>(Map.of("id", "pref_saved", "kind", "ASSERT_TEXT", "selector", "[data-testid='prefs-saved']", "value", "Saved")));
        ((Map<String, Object>) data.get("acceptance")).put("browser", browser);
        ((List<Map<String, Object>>) data.get("routes")).add(new LinkedHashMap<>(Map.of(
                "path", "/settings", "componentPath", "client/src/App.tsx", "owner", "ui")));
        ((List<Map<String, Object>>) data.get("units")).getLast().put("acceptanceIds",
                browser.stream().map(action -> action.get("id")).toList());
        return data;
    }
    @Test void exactDuplicateChecksAndUnitsAreRemovedWithoutLosingCrudCoverage() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");units.add(new LinkedHashMap<>(units.getFirst()));
        Map<String,Object> acceptance=(Map<String,Object>)data.get("acceptance");
        var browser=(List<Object>)acceptance.get("browser");browser.add(browser.getFirst());
        var api=(List<Object>)acceptance.get("api");api.add(api.getLast());
        var repaired=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(original, repaired);
        assertTrue(repaired.acceptance().crudRequired());
        assertTrue(repaired.acceptance().api().stream().anyMatch(step->step.kind()==BehaviorCriteria.ApiKind.DELETE));
    }
    @Test void distinctDuplicateUnitIdsAreRenumberedOnlyWithUnambiguousPathOwners() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");
        units.getLast().put("id","data");units.getLast().put("dependsOn",List.of());
        ((List<Map<String,Object>>)data.get("routes")).getFirst().put("owner","data");
        var repaired=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(List.of("data","data_2"),repaired.units().stream().map(BuildContract.Unit::id).toList());
        assertEquals("data_2",repaired.routes().getFirst().owner());
        assertEquals("data",repaired.api().getFirst().owner());
        assertEquals(original.acceptance(),repaired.acceptance());
    }
    @Test void ambiguousUnitDependenciesAndUniqueOverflowStillNeedModelCorrection() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");units.getLast().put("id","data");
        ((List<Map<String,Object>>)data.get("routes")).getFirst().put("owner","data");
        var unresolved=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper);
        assertThrows(RuntimeException.class,()->mapper.readValue(unresolved,BuildContract.class));

        data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        Map<String,Object> acceptance=(Map<String,Object>)data.get("acceptance");
        var browser=(List<Map<String,Object>>)acceptance.get("browser");
        for(int index=1;index<=24;index++){
            var action=new LinkedHashMap<>(browser.getFirst());action.put("id","required_"+index);
            action.put("value","Required evidence "+index);browser.add(action);
        }
        String oversized=mapper.writeValueAsString(data);
        assertThrows(RuntimeException.class,()->ContractAcceptanceCompiler.completeCollections(oversized,mapper));
        assertEquals(25,((List<?>)((Map<?,?>)data.get("acceptance")).get("browser")).size());
    }
    @Test void missingResponsibilitiesUseExistingOwnersWithoutGrantingFilesOrDroppingChecks(){
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        for(Object value:(List<?>)data.get("units"))((Map<String,Object>)value).put("acceptanceIds",List.of());
        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(original.ownership(),compiled.ownership());assertEquals(original.acceptance(),compiled.acceptance());
        assertEquals(Set.copyOf(original.units().getFirst().acceptanceIds()),Set.copyOf(compiled.units().getFirst().acceptanceIds()));assertEquals(List.of("ui"),compiled.units().getLast().acceptanceIds());
    }
    @Test void oneUnitCanOwnTheFullBoundedBrowserAndApiAcceptanceSet() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");var apiOwner=units.getFirst();var uiOwner=units.getLast();
        apiOwner.put("acceptanceIds",List.of());uiOwner.put("acceptanceIds",List.of());
        var apiPaths=new ArrayList<String>((List<String>)apiOwner.get("ownedPaths"));apiPaths.add("client/src/App.tsx");apiOwner.put("ownedPaths",apiPaths);
        var uiPaths=new ArrayList<String>((List<String>)uiOwner.get("ownedPaths"));uiPaths.remove("client/src/App.tsx");uiPaths.add("client/src/components/Unused.tsx");uiOwner.put("ownedPaths",uiPaths);
        var routes=(List<Map<String,Object>>)data.get("routes");routes.getFirst().put("owner",apiOwner.get("id"));
        Map<String,Object> acceptance=mapper.convertValue(data.get("acceptance"),Map.class);
        var apiChecks=new ArrayList<Object>((List<?>)acceptance.get("api"));
        for(int i=0;i<17;i++)apiChecks.add(new BehaviorCriteria.ApiStep("extra_"+i,BehaviorCriteria.ApiKind.READ,"GET",
                "/api/items/${itemId}",null,200,null,null,null,"/value","one"));
        acceptance.put("api",apiChecks);data.put("acceptance",acceptance);

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(25,compiled.units().getFirst().acceptanceIds().size());
        assertEquals(24,compiled.acceptance().api().size());
        assertTrue(compiled.units().getFirst().acceptanceIds().contains("ui"));
        assertEquals(original.units().getFirst().ownedPaths().size()+1,compiled.units().getFirst().ownedPaths().size());
    }
    @Test void coordinatorOwnedMernEntryPointIsRemovedFromUnitOwnership() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");var owner=units.getFirst();
        var paths=new ArrayList<String>((List<String>)owner.get("ownedPaths"));paths.add("server/src/index.ts");owner.put("ownedPaths",paths);

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertFalse(compiled.ownership().containsKey("server/src/index.ts"));
        assertEquals(original.ownership().keySet(),compiled.ownership().keySet());
    }
    @Test void missingCollectionCoverageIsStrengthenedWithoutAnotherModelCallOrGrant() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var apis=new ArrayList<Object>((List<?>)data.get("api"));apis.add(new BuildContract.Api("list","GET","/api/items",null,"Item[]","data","server/src/items.ts","itemsRouter","/api/items",false));data.put("api",apis);
        String proposal=mapper.writeValueAsString(data);assertThrows(RuntimeException.class,()->mapper.readValue(proposal,BuildContract.class));
        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(proposal,mapper),BuildContract.class);
        assertEquals(original.units().stream().map(BuildContract.Unit::ownedPaths).toList(),compiled.units().stream().map(BuildContract.Unit::ownedPaths).toList());assertEquals(original.limits(),compiled.limits());
        var check=compiled.acceptance().api().stream().filter(step->step.id().equals("collection_list")).findFirst().orElseThrow();
        assertEquals("/api/items",check.path());assertEquals("/0/value",check.assertPointer());assertEquals("one",check.assertValue());
        assertTrue(compiled.units().getFirst().acceptanceIds().contains(check.id()));assertEquals(original.acceptance().api().size()+1,compiled.acceptance().api().size());
        assertEquals(compiled,mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(compiled),mapper),BuildContract.class));
    }
    @Test void aCollectionCannotBeCertifiedFromAnUnrelatedOrMissingFixture() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var apis=new ArrayList<Object>((List<?>)data.get("api"));apis.add(new BuildContract.Api("other","GET","/api/other",null,"Item[]","data","server/src/items.ts","itemsRouter","/api",false));data.put("api",apis);
        String proposal=mapper.writeValueAsString(data);assertEquals(proposal,ContractAcceptanceCompiler.completeCollections(proposal,mapper));
        assertThrows(RuntimeException.class,()->mapper.readValue(proposal,BuildContract.class));
    }

    @Test void wrappedListAssertionsFollowTheDeclaredResponseObjectWithoutAnotherModelCall() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var apis=new ArrayList<Object>((List<?>)data.get("api"));
        apis.add(new BuildContract.Api("list","GET","/api/items",null,"ItemListResponse","data","server/src/items.ts","itemsRouter","/api/items",false));
        data.put("api",apis);
        var types=new ArrayList<Object>((List<?>)data.get("types"));
        types.add(new BuildContract.DataType("ItemListResponse",List.of(
                new BuildContract.Field("items",BuildContract.Kind.JSON_ARRAY,false),
                new BuildContract.Field("total",BuildContract.Kind.NUMBER,false))));
        data.put("types",types);
        Map<String,Object> acceptance=new LinkedHashMap<>(mapper.convertValue(data.get("acceptance"),Map.class));
        var checks=new ArrayList<Object>((List<?>)acceptance.get("api"));
        checks.add(new BehaviorCriteria.ApiStep("list", BehaviorCriteria.ApiKind.READ,"GET","/api/items",null,200,
                null,null,null,"/0/value","one"));
        acceptance.put("api",checks);data.put("acceptance",acceptance);

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        var list=compiled.acceptance().api().stream().filter(step->step.id().equals("list")).findFirst().orElseThrow();
        assertEquals("/items/0/value",list.assertPointer());
        assertTrue(compiled.units().getFirst().acceptanceIds().contains("list"));
        assertEquals(original.limits(),compiled.limits());
    }

    @Test void wrappedListPointerIsNotGuessedWhenResponseHasMultipleArrays() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var apis=new ArrayList<Object>((List<?>)data.get("api"));
        apis.add(new BuildContract.Api("list","GET","/api/items",null,"ItemListResponse","data","server/src/items.ts","itemsRouter","/api/items",false));
        data.put("api",apis);
        var types=new ArrayList<Object>((List<?>)data.get("types"));
        types.add(new BuildContract.DataType("ItemListResponse",List.of(
                new BuildContract.Field("items",BuildContract.Kind.JSON_ARRAY,false),
                new BuildContract.Field("recent",BuildContract.Kind.JSON_ARRAY,true))));
        data.put("types",types);
        Map<String,Object> acceptance=new LinkedHashMap<>(mapper.convertValue(data.get("acceptance"),Map.class));
        var checks=new ArrayList<Object>((List<?>)acceptance.get("api"));
        checks.add(new BehaviorCriteria.ApiStep("list", BehaviorCriteria.ApiKind.READ,"GET","/api/items",null,200,
                null,null,null,"/0/value","one"));
        acceptance.put("api",checks);data.put("acceptance",acceptance);

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        var list=compiled.acceptance().api().stream().filter(step->step.id().equals("list")).findFirst().orElseThrow();
        assertEquals("/0/value",list.assertPointer());
    }

    @Test void apiAndRouteOwnersFollowTheSingleExactOwnerOfTheirFileWithoutGrantingPaths() {
        // Batch 01/25 shape: endpoints and routes name the calling page unit, not the file owner.
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var units=(List<Map<String,Object>>)data.get("units");units.forEach(unit->unit.put("acceptanceIds",List.of()));
        units.add(new LinkedHashMap<>(Map.of("id","page","objective","Implement page","ownedPaths",List.of("client/src/pages/Edit.tsx"),
                "imports",List.of(),"dependsOn",List.of("data"),"acceptanceIds",List.of())));
        var apis=(List<Map<String,Object>>)data.get("api");
        apis.stream().filter(api->Set.of("GET","PUT").contains(api.get("method"))).forEach(api->api.put("owner","page"));
        var routes=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)data.get("routes"));
        for(String path:List.of("/items/:id/edit","/items/new"))routes.add(new LinkedHashMap<>(Map.of("path",path,"componentPath","client/src/pages/Edit.tsx","owner","ui")));
        data.put("routes",routes);
        var paths=units.stream().map(unit->unit.get("ownedPaths")).toList();

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(Set.of("data"),compiled.api().stream().map(BuildContract.Api::owner).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of("ui","page","page"),compiled.routes().stream().map(BuildContract.Route::owner).toList());
        assertEquals(paths,compiled.units().stream().map(BuildContract.Unit::ownedPaths).toList());
        assertEquals(original.acceptance(),compiled.acceptance());
        assertTrue(compiled.units().getFirst().acceptanceIds().containsAll(List.of("read","update","restart","missing")));
    }

    @Test void ownerAlignmentRunsBeforeCollectionCoverageAndForNonCrudContracts() {
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        var apis=new ArrayList<Object>((List<?>)data.get("api"));apis.add(new BuildContract.Api("list","GET","/api/items",null,"Item[]","ui","server/src/items.ts","itemsRouter","/api/items",false));data.put("api",apis);
        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals("data",compiled.api().getLast().owner());
        assertTrue(compiled.units().getFirst().acceptanceIds().contains("collection_list"));

        data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        ((Map<String,Object>)data.get("acceptance")).put("crudRequired",false);
        ((List<Map<String,Object>>)data.get("api")).getLast().put("owner","ui");
        Map<String,Object> aligned=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
        assertEquals("data",((List<Map<String,Object>>)aligned.get("api")).getLast().get("owner"));
    }

    @Test void unownedSharedReservedCaseVariantOrInconsistentRoutersAndUndeclaredOwnersAreNotAligned() {
        var original=ContractFixtures.mern();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        // Unowned router without model-call budget for a synthesized server unit.
        cases.add(data->{units(data).getFirst().put("ownedPaths",List.of("server/src/models/item.ts"));data.put("limits",limits(3));});
        // Shared router that the unit merge leaves alone (over its 6-file cap).
        cases.add(data->units(data).getLast().put("ownedPaths",List.of("client/src/App.tsx","server/src/items.ts","client/src/A.tsx",
                "client/src/B.tsx","client/src/C.tsx","client/src/D.tsx","client/src/E.tsx")));
        cases.add(data->units(data).getFirst().put("ownedPaths",List.of("server/src/Items.ts")));
        cases.add(data->{units(data).getFirst().put("ownedPaths",List.of("server/src/index.ts"));
                api(data).forEach(api->api.put("routerPath","server/src/index.ts"));});
        cases.add(data->api(data).getFirst().put("routerExport","otherRouter"));
        cases.add(data->api(data).get(1).put("owner","EditItem"));
        for(var mutation:cases){
            Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
            mutation.accept(data);api(data).getLast().put("owner","ui");
            var owners=api(data).stream().map(api->api.get("owner")).toList();
            Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(owners,api(result).stream().map(api->api.get("owner")).toList());
            assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
        }
        Map<String,Object> vite=mapper.readValue(mapper.writeValueAsString(ContractFixtures.vite()),Map.class);
        units(vite).add(new LinkedHashMap<>(Map.of("id","other","objective","Implement other","ownedPaths",List.of("src/Other.tsx"),
                "imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));
        ((List<Map<String,Object>>)vite.get("routes")).getFirst().put("owner","other");
        String proposal=mapper.writeValueAsString(vite);
        assertEquals(proposal,ContractAcceptanceCompiler.completeCollections(proposal,mapper));
    }

    @Test void unownedRouterGetsOneDedicatedServerUnitInsteadOfAPageGrant() {
        // Batch 05/10/13/14 shape: no unit owns the router and endpoint owners are split across calling pages.
        var original=ContractFixtures.mern();Map<String,Object> data=unownedRouter(original);
        api(data).get(1).remove("owner");
        var paths=units(data).stream().map(unit->unit.get("ownedPaths")).toList();

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        var server=compiled.units().getLast();
        assertEquals(new BuildContract.Unit("itemsRouter","Implement itemsRouter Express router for /api/items",
                List.of("server/src/items.ts"),List.of(),List.of(),server.acceptanceIds()),server);
        assertEquals(paths,compiled.units().stream().limit(3).map(BuildContract.Unit::ownedPaths).toList());
        assertEquals(Set.of("itemsRouter"),compiled.api().stream().map(BuildContract.Api::owner).collect(java.util.stream.Collectors.toSet()));
        assertEquals(original.acceptance(),compiled.acceptance());
        assertTrue(server.acceptanceIds().containsAll(List.of("create","read","update","restart","delete","missing")));
        assertTrue(compiled.units().stream().noneMatch(unit->unit.dependsOn().contains("itemsRouter")));
    }

    @Test void ambiguousProtectedOrOverBudgetUnownedRoutersAreNotGivenAServerUnit() {
        var original=ContractFixtures.mern();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        cases.add(data->units(data).get(1).put("id","itemsRouter"));
        cases.add(data->units(data).getFirst().put("ownedPaths",List.of("server/src/Items.ts")));
        cases.add(data->units(data).getFirst().put("ownedPaths",List.of("server/src")));
        cases.add(data->api(data).forEach(api->api.put("routerPath","server/src/index.ts")));
        cases.add(data->api(data).forEach(api->api.put("routerPath","server/src/rendra/items.ts")));
        cases.add(data->api(data).forEach(api->api.put("routerPath","client/src/items.ts")));
        cases.add(data->api(data).getFirst().put("routerExport","otherRouter"));
        cases.add(data->api(data).getFirst().put("basePath","/api"));
        cases.add(data->api(data).get(1).put("owner","EditItem"));
        cases.add(data->api(data).forEach(api->{api.put("basePath","/api/auth");api.put("path",((String)api.get("path")).replace("/api/items","/api/auth"));}));
        cases.add(data->data.put("limits",limits(4)));
        cases.add(data->{for(int i=units(data).size();i<12;i++)units(data).add(new LinkedHashMap<>(Map.of("id","extra"+i,"objective","Implement extra",
                "ownedPaths",List.of("client/src/Extra"+i+".tsx"),"imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));});
        for(var mutation:cases){
            Map<String,Object> data=unownedRouter(original);mutation.accept(data);
            var units=units(data).stream().map(unit->unit.get("id")).toList();
            var owners=api(data).stream().map(api->api.get("owner")).toList();
            Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(units,units(result).stream().map(unit->unit.get("id")).toList());
            assertEquals(owners,api(result).stream().map(api->api.get("owner")).toList());
            assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
        }
    }

    private Map<String,Object> unownedRouter(BuildContract original){
        Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        units(data).forEach(unit->unit.put("acceptanceIds",List.of()));
        units(data).getFirst().put("ownedPaths",List.of("client/src/pages/Home.tsx"));
        units(data).add(new LinkedHashMap<>(Map.of("id","page","objective","Implement page","ownedPaths",List.of("client/src/pages/Edit.tsx"),
                "imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));
        api(data).stream().filter(api->Set.of("GET","PUT").contains(api.get("method"))).forEach(api->api.put("owner","page"));
        return data;
    }
    @Test void unitsSharingAPageComponentMergeIntoTheFirstDeclaredUnit() {
        // Batch 03/16 shape: two page units list one shared component and one depends on the other.
        var original=ContractFixtures.mern();Map<String,Object> data=sharedComponent(original);
        units(data).add(new LinkedHashMap<>(Map.of("id","other","objective","Implement other","ownedPaths",List.of("client/src/Other.tsx"),
                "imports",List.of("client/src/List.tsx"),"dependsOn",List.of("list","ui"),"acceptanceIds",List.of())));

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(List.of("data","ui","other"),compiled.units().stream().map(BuildContract.Unit::id).toList());
        var ui=compiled.units().get(1);
        assertEquals(new BuildContract.Unit("ui","Implement ui; Show the list",List.of("client/src/App.tsx","client/src/List.tsx"),
                List.of("server/src/items.ts"),List.of("data"),List.of("ui","listed")),ui);
        assertEquals(List.of("ui"),compiled.units().getLast().dependsOn());
        assertEquals(List.of("ui","ui"),compiled.routes().stream().map(BuildContract.Route::owner).toList());
        assertEquals(original.acceptance().api(),compiled.acceptance().api());
    }

    @Test void routerSharedAcrossPageUnitsMergesWithTheRouterOwnerAndKeepsEveryCheck() {
        // Batch 07/22 shape: the router and a component are each listed by two units; api owners follow the merge.
        var original=ContractFixtures.mern();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        units(data).getLast().put("ownedPaths",List.of("client/src/App.tsx","client/src/List.tsx"));
        units(data).add(new LinkedHashMap<>(Map.of("id","list","objective","Fetch the list.","ownedPaths",
                List.of("client/src/List.tsx","server/src/items.ts"),"imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));
        api(data).stream().filter(api->Set.of("GET","PUT").contains(api.get("method"))).forEach(api->api.put("owner","list"));

        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        var unit=compiled.units().getFirst();
        assertEquals(1,compiled.units().size());
        assertEquals(List.of("server/src/items.ts","client/src/App.tsx","client/src/List.tsx"),unit.ownedPaths());
        assertEquals("Implement data; Implement ui; Fetch the list",unit.objective());
        assertEquals(List.of(),unit.dependsOn());
        assertEquals(Set.of("data"),compiled.api().stream().map(BuildContract.Api::owner).collect(java.util.stream.Collectors.toSet()));
        assertEquals("data",compiled.routes().getFirst().owner());
        assertEquals(original.acceptance(),compiled.acceptance());
        assertTrue(unit.acceptanceIds().containsAll(original.acceptance().api().stream().map(BehaviorCriteria.ApiStep::id).toList()));
    }

    @Test void protectedLargeCaseVariantBatchedCyclicSelfDependentOrAssetOwningGroupsAreNotMerged() {
        var original=ContractFixtures.mern();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        for(String path:List.of("client/src/rendra/List.tsx","server/src/rendra/list.ts",StaticAssets.MARKER,"rendra.css"))
            cases.add(data->{units(data).get(1).put("ownedPaths",List.of("client/src/App.tsx",path));units(data).getLast().put("ownedPaths",List.of(path));
                ((List<Map<String,Object>>)data.get("routes")).getLast().put("componentPath",path);});
        cases.add(data->units(data).getLast().put("ownedPaths",List.of("client/src/List.tsx","client/src/A.tsx","client/src/B.tsx",
                "client/src/C.tsx","client/src/D.tsx","client/src/E.tsx")));
        cases.add(data->{units(data).get(1).put("ownedPaths",List.of("client/src/App.tsx","client/src/List.tsx","client/src/Form.tsx"));
                units(data).getLast().put("ownedPaths",List.of("client/src/List.tsx","client/src/form.tsx"));});
        cases.add(data->data.put("batches",List.of(Map.of("id","b1","unitIds",List.of("data","ui","list")))));
        cases.add(data->{units(data).getLast().put("dependsOn",List.of("other"));units(data).add(new LinkedHashMap<>(Map.of("id","other",
                "objective","Implement other","ownedPaths",List.of("client/src/Other.tsx"),"imports",List.of(),"dependsOn",List.of("ui"),"acceptanceIds",List.of())));});
        cases.add(data->units(data).getLast().put("dependsOn",List.of("list")));
        cases.add(data->data.put("assets",List.of(Map.of("source","assets/logo.png","destination","client/src/List.tsx",
                "fingerprint","sha256:"+"a".repeat(64),"owner","list"))));
        for(var mutation:cases){
            Map<String,Object> data=sharedComponent(original);mutation.accept(data);
            var units=units(data).stream().map(unit->unit.get("id")).toList();
            var owners=((List<Map<String,Object>>)data.get("routes")).stream().map(route->route.get("owner")).toList();
            Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(units,units(result).stream().map(unit->unit.get("id")).toList());
            assertEquals(owners,((List<Map<String,Object>>)result.get("routes")).stream().map(route->route.get("owner")).toList());
            assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
        }
    }

    private Map<String,Object> sharedComponent(BuildContract original){
        Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        units(data).getLast().put("ownedPaths",List.of("client/src/App.tsx","client/src/List.tsx"));
        units(data).add(new LinkedHashMap<>(Map.of("id","list","objective","Show the list.","ownedPaths",List.of("client/src/List.tsx"),
                "imports",List.of("server/src/items.ts"),"dependsOn",List.of("ui","data"),"acceptanceIds",List.of("listed"))));
        var browser=new ArrayList<Object>((List<?>)((Map<String,Object>)data.get("acceptance")).get("browser"));
        browser.add(Map.of("id","listed","kind","ASSERT_EXISTS","selector","#list"));
        ((Map<String,Object>)data.get("acceptance")).put("browser",browser);
        var routes=new ArrayList<Object>((List<?>)data.get("routes"));
        routes.add(new LinkedHashMap<>(Map.of("path","/list","componentPath","client/src/List.tsx","owner","list")));
        data.put("routes",routes);
        return data;
    }

    @Test void missingRecordReadIsDeclaredFromItsUnambiguousSiblingsAndStaysIdempotent() {
        var original=ContractFixtures.accounts();
        var expected=new BuildContract.Api("getItem","GET","/api/items/:id",null,"Item","data","server/src/items.ts","itemsRouter","/api/items",true);
        String once=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(missingRecordRead(original)),mapper);
        var compiled=mapper.readValue(once,BuildContract.class);
        assertEquals(expected,compiled.api().getLast());
        assertEquals(original.api().size(),compiled.api().size());
        assertEquals(original.acceptance(),compiled.acceptance());
        assertTrue(compiled.units().getFirst().acceptanceIds().containsAll(List.of("read","restart","missing")));
        assertEquals(once,ContractAcceptanceCompiler.completeCollections(once,mapper));

        // Batch 20 shape: PATCH sibling and a list GET of the same record type.
        Map<String,Object> data=missingRecordRead(original);
        api(data).stream().filter(api->"PUT".equals(api.get("method"))).forEach(api->api.put("method","PATCH"));
        var steps=(List<Map<String,Object>>)((Map<String,Object>)data.get("acceptance")).get("api");
        steps.stream().filter(step->"PUT".equals(step.get("method"))).forEach(step->step.put("method","PATCH"));
        var list=new LinkedHashMap<String,Object>(api(data).getFirst());list.putAll(Map.of("id","getItems","method","GET","path","/api/items","responseType","Item[]"));list.put("requestType",null);
        api(data).add(0,list);
        compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(expected,compiled.api().getLast());
        assertTrue(compiled.acceptance().api().stream().anyMatch(step->step.id().equals("collection_getItems")));
    }

    @Test void recordReadIsNeverDeclaredForNotFoundOnlyAmbiguousUnguardedOrUntypedSiblings() {
        var original=ContractFixtures.accounts();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        // Batch 21 shape without a grounded CREATE assertion: READ/PERSISTENCE use the list, so only NOT_FOUND requests the record path.
        cases.add(data->{var list=new LinkedHashMap<String,Object>(api(data).getFirst());list.putAll(Map.of("id","getItems","method","GET","path","/api/items","responseType","Item[]"));
                list.put("requestType",null);api(data).add(0,list);((List<Map<String,Object>>)((Map<String,Object>)data.get("acceptance")).get("api")).stream()
                .filter(step->Set.of("read","restart").contains(step.get("id"))).forEach(step->{step.put("path","/api/items");step.put("assertPointer","/0/value");});});
        cases.addAll(siblingDisagreements());
        cases.add(data->{var list=new LinkedHashMap<String,Object>(api(data).getFirst());list.putAll(Map.of("id","getItems","method","GET","path","/api/items","responseType","ItemInput[]"));api(data).add(list);});
        cases.add(data->api(data).getFirst().put("id","getItem"));
        cases.add(data->{for(int i=api(data).size();i<24;i++){var extra=new LinkedHashMap<String,Object>(api(data).getFirst());
                extra.putAll(Map.of("id","extra"+i,"method","GET","path","/api/items/extra"+i));api(data).add(extra);}});
        for(var mutation:cases){
            Map<String,Object> data=missingRecordRead(original);mutation.accept(data);
            var before=api(data).stream().map(api->api.get("method")+" "+api.get("path")).toList();
            Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(before,api(result).stream().map(api->api.get("method")+" "+api.get("path")).toList());
            assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
        }
    }

    @Test void notFoundRecordPathGetsItsGetAndAReadMirroredFromTheGroundedListRead() {
        // Batch r2-21 shape: CREATE asserts the field the list READ finds at /0, and only NOT_FOUND requests the record path.
        var original=ContractFixtures.accounts();Map<String,Object> data=groundedNotFound(original);
        assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(data),BuildContract.class));
        String[] once=new String[1];
        var events=capture(()->once[0]=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper));
        var compiled=mapper.readValue(once[0],BuildContract.class);
        assertEquals(new BuildContract.Api("getItem","GET","/api/items/:id",null,"Item","data","server/src/items.ts","itemsRouter","/api/items",true),compiled.api().getLast());
        assertEquals(api(data).size()+1,compiled.api().size());
        var checks=new ArrayList<>(compiled.acceptance().api());
        var read=checks.stream().filter(step->step.id().equals("read")).findFirst().orElseThrow();
        assertEquals(new BehaviorCriteria.ApiStep("record_getItem",BehaviorCriteria.ApiKind.READ,"GET","/api/items/${itemId}",null,200,
                read.tokenVariable(),null,null,"/value","one"),checks.get(checks.indexOf(read)+1));
        assertEquals("sessionToken",read.tokenVariable());
        checks.removeIf(step->step.id().equals("record_getItem"));
        assertEquals(steps(data).stream().map(step->mapper.convertValue(step,BehaviorCriteria.ApiStep.class)).toList(),checks);
        assertTrue(compiled.units().getFirst().acceptanceIds().containsAll(List.of("record_getItem","missing")));
        assertTrue(events.contains("planner_record_read_declared id=getItem path=/api/items/:id owner=data responseType=Item checks=[record_getItem, missing]"),events.toString());
        assertTrue(events.contains("planner_record_read_check_inserted id=record_getItem path=/api/items/:id from=read"),events.toString());
        assertEquals(once[0],ContractAcceptanceCompiler.completeCollections(once[0],mapper));
    }

    @Test void notFoundRecordPathIsNotGroundedByAmbiguousMismatchedOrOverBudgetLists() {
        var original=ContractFixtures.accounts();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>(siblingDisagreements());
        cases.add(data->step(data,"create").put("assertValue","other"));
        cases.add(data->{var copy=new LinkedHashMap<>(step(data,"read"));copy.put("id","read2");steps(data).add(steps(data).indexOf(step(data,"read"))+1,copy);});
        cases.add(data->{var copy=new LinkedHashMap<>(step(data,"create"));copy.putAll(Map.of("id","create2","captureVariable","otherId"));steps(data).add(steps(data).indexOf(step(data,"create"))+1,copy);});
        cases.add(data->api(data).removeIf(api->"getItems".equals(api.get("id"))));
        cases.add(data->api(data).getFirst().put("responseType","Item"));
        cases.add(data->api(data).getFirst().put("responseType","ItemInput[]"));
        cases.add(data->step(data,"read").put("assertPointer","/0"));
        cases.add(data->step(data,"read").put("assertPointer","/0/value/x"));
        cases.add(data->api(data).getFirst().put("id","getItem"));
        cases.add(data->{for(int i=steps(data).size();i<24;i++){var extra=new LinkedHashMap<>(steps(data).getFirst());extra.put("id","extra"+i);steps(data).add(1,extra);}});
        cases.add(data->{for(int i=api(data).size();i<24;i++){var extra=new LinkedHashMap<String,Object>(api(data).get(1));
                extra.putAll(Map.of("id","extra"+i,"method","GET","path","/api/items/extra"+i));api(data).add(extra);}});
        for(var mutation:cases){
            Map<String,Object> data=groundedNotFound(original);mutation.accept(data);
            var before=api(data).stream().map(api->api.get("method")+" "+api.get("path")).toList();
            var ids=steps(data).stream().map(step->step.get("id")).toList();
            Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(before,api(result).stream().map(api->api.get("method")+" "+api.get("path")).toList());
            assertEquals(ids,steps(result).stream().map(step->step.get("id")).toList());
            assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
        }
        // A declared record GET or a 2xx record READ leaves nothing for the grounded mirror to do.
        Map<String,Object> declared=groundedNotFound(original);
        var get=new LinkedHashMap<String,Object>(api(declared).getFirst());get.putAll(Map.of("id","getItem","path","/api/items/:id","responseType","Item"));api(declared).add(get);
        Map<String,Object> kept=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(declared),mapper),Map.class);
        assertEquals(api(declared).size(),api(kept).size());
        assertEquals(steps(declared).stream().map(step->step.get("id")).toList(),steps(kept).stream().map(step->step.get("id")).toList());
        Map<String,Object> detailed=groundedNotFound(original);
        var detail=new LinkedHashMap<>(step(detailed,"read"));detail.putAll(Map.of("id","detail","path","/api/items/${itemId}","assertPointer","/value"));
        steps(detailed).add(steps(detailed).indexOf(step(detailed,"read"))+1,detail);
        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(detailed),mapper),BuildContract.class);
        assertEquals("getItem",compiled.api().getLast().id());
        assertEquals(steps(detailed).stream().map(step->step.get("id")).toList(),compiled.acceptance().api().stream().map(BehaviorCriteria.ApiStep::id).toList());
    }

    private static List<java.util.function.Consumer<Map<String,Object>>> siblingDisagreements(){
        return List.of(data->api(data).stream().filter(api->((String)api.get("path")).contains(":")).forEach(api->api.put("owner","ghost")),
                data->api(data).stream().filter(api->"PUT".equals(api.get("method"))).forEach(api->api.put("basePath","/api")),
                data->api(data).stream().filter(api->"DELETE".equals(api.get("method"))).forEach(api->api.put("routerPath","server/src/other.ts")),
                data->{data.put("auth","NONE");api(data).forEach(api->api.put("authenticated",false));},
                data->api(data).stream().filter(api->"PUT".equals(api.get("method"))).forEach(api->api.put("responseType","Thing")),
                data->api(data).stream().filter(api->"PUT".equals(api.get("method"))).forEach(api->api.put("responseType","Item[]")),
                data->api(data).removeIf(api->"PUT".equals(api.get("method"))));
    }

    private Map<String,Object> groundedNotFound(BuildContract original){
        Map<String,Object> data=missingRecordRead(original);
        var list=new LinkedHashMap<String,Object>(api(data).getFirst());list.putAll(Map.of("id","getItems","method","GET","path","/api/items","responseType","Item[]"));
        list.put("requestType",null);api(data).add(0,list);
        step(data,"create").putAll(Map.of("assertPointer","/value","assertValue","one"));
        for(String id:List.of("read","restart"))step(data,id).putAll(Map.of("path","/api/items","assertPointer","/0/value"));
        return data;
    }
    private static List<Map<String,Object>> steps(Map<String,Object> data){return (List<Map<String,Object>>)((Map<String,Object>)data.get("acceptance")).get("api");}
    private static Map<String,Object> step(Map<String,Object> data,String id){return steps(data).stream().filter(step->id.equals(step.get("id"))).findFirst().orElseThrow();}

    @Test void platformChecksFallBackToTheFirstApiOwnedByAUnit() {
        // Batch 22 shape: the first declared api names a page that is not a unit.
        var original=ContractFixtures.accounts();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        units(data).getFirst().put("acceptanceIds",List.of());
        var notes=new LinkedHashMap<String,Object>(api(data).getFirst());
        notes.putAll(Map.of("id","notes","method","GET","path","/api/notes","responseType","Item[]","owner","Notes",
                "routerPath","server/src/notes.ts","routerExport","notesRouter","basePath","/api/notes"));
        api(data).add(0,notes);
        Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
        assertTrue(((List<?>)units(result).getFirst().get("acceptanceIds")).containsAll(List.of("health","signup","login","wrongPassword")));
        assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(result),BuildContract.class));
    }

    @Test void accountDataApisAreBoundToTheCoordinatorGuardAndStayIdempotent() {
        // Batch 17-19 shape: public list/detail reads beside guarded writes in an ACCOUNTS app.
        var original=ContractFixtures.accounts();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        api(data).stream().filter(api->"GET".equals(api.get("method"))).forEach(api->api.put("authenticated",false));
        assertThrows(RuntimeException.class,()->mapper.readValue(mapper.writeValueAsString(data),BuildContract.class));
        String once=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper);
        assertEquals(original,mapper.readValue(once,BuildContract.class));
        assertEquals(once,ContractAcceptanceCompiler.completeCollections(once,mapper));
    }

    @Test void authGuardIsNeverBoundWithoutAccountsOnAuthPathsOrForNonBooleanFlags() {
        Map<String,Object> open=mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()),Map.class);
        Map<String,Object> result=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(open),mapper),Map.class);
        assertTrue(api(result).stream().allMatch(api->Boolean.FALSE.equals(api.get("authenticated"))));
        var accounts=ContractFixtures.accounts();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        cases.add(api->{api.putAll(Map.of("path","/api/auth/profile","basePath","/api/auth","authenticated",false));});
        cases.add(api->{api.putAll(Map.of("path","/api/auth","basePath","/api/auth","authenticated",false));});
        cases.add(api->api.put("authenticated","false"));
        for(var mutation:cases){
            Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(accounts),Map.class);
            mutation.accept(api(data).getFirst());var before=new LinkedHashMap<>(api(data).getFirst());
            Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            assertEquals(before,api(compiled).stream().filter(api->before.get("id").equals(api.get("id"))).findFirst().orElseThrow());
        }
        Map<String,Object> missing=mapper.readValue(mapper.writeValueAsString(accounts),Map.class);
        api(missing).getFirst().put("authenticated",null);
        assertThrows(RuntimeException.class,()->ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(missing),mapper));
    }

    @Test void duplicateApiIdsAreRenamedFromEachEndpointsOwnMethodAndPath() {
        // Batch r2 10 shape: every api entry repeats the owning unit id.
        var original=ContractFixtures.accounts();Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        api(data).forEach(api->api.put("id","data"));
        String proposal=mapper.writeValueAsString(data);
        var direct=assertThrows(RuntimeException.class,()->mapper.readValue(proposal,BuildContract.class));
        assertEquals("api: duplicate api id",ContractPlanner.rejectionSignature(direct));
        var events=capture(()->{
            String once=ContractAcceptanceCompiler.completeCollections(proposal,mapper);
            var compiled=mapper.readValue(once,BuildContract.class);
            assertEquals(List.of("post_items","get_items_id","put_items_id","delete_items_id"),compiled.api().stream().map(BuildContract.Api::id).toList());
            assertEquals(original.api().stream().map(api->new BuildContract.Api("x",api.method(),api.path(),api.requestType(),api.responseType(),api.owner(),api.routerPath(),api.routerExport(),api.basePath(),api.authenticated())).toList(),
                    compiled.api().stream().map(api->new BuildContract.Api("x",api.method(),api.path(),api.requestType(),api.responseType(),api.owner(),api.routerPath(),api.routerExport(),api.basePath(),api.authenticated())).toList());
            assertEquals(original.units(),compiled.units());assertEquals(original.acceptance(),compiled.acceptance());
            assertEquals(once,ContractAcceptanceCompiler.completeCollections(once,mapper));
        });
        assertEquals(List.of("planner_duplicate_api_ids_renamed id=data ids=[post_items, get_items_id, put_items_id, delete_items_id] count=4"),events);
    }

    @Test void sameMethodAndPathCollidingOrUniqueApiIdsAreNotRenamed() {
        var original=ContractFixtures.accounts();
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        cases.add(data->{var copy=new LinkedHashMap<String,Object>(api(data).get(1));copy.put("responseType","Item[]");api(data).add(copy);
                api(data).forEach(api->api.put("id","data"));});
        cases.add(data->{api(data).forEach(api->api.put("id","data"));var list=new LinkedHashMap<String,Object>(api(data).get(1));
                list.putAll(Map.of("id","get_items_id","path","/api/items/:id/history"));api(data).add(list);});
        cases.add(data->{var history=new LinkedHashMap<String,Object>(api(data).get(1));history.put("path","/api/items/:id/"+"a".repeat(40));
                api(data).add(history);api(data).forEach(api->api.put("id","data"));});
        for(var mutation:cases){
            Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);mutation.accept(data);
            var before=api(data).stream().map(api->api.get("id")).toList();
            var events=capture(()->{
                String result=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper);
                Map<String,Object> compiled=mapper.readValue(result,Map.class);assertEquals(before,api(compiled).stream().map(api->api.get("id")).toList());
                var failure=assertThrows(RuntimeException.class,()->mapper.readValue(result,BuildContract.class));
                assertEquals("api: duplicate api id",ContractPlanner.rejectionSignature(failure));
            });
            assertTrue(events.stream().noneMatch(event->event.startsWith("planner_duplicate_api_ids_renamed")));
        }
        String unique=mapper.writeValueAsString(original);
        assertEquals(List.of(),capture(()->assertEquals(unique,ContractAcceptanceCompiler.completeCollections(unique,mapper))));
    }

    @Test void uniqueCreateClickBeforeUiCreatedBecomesUiAddWithoutMovingOrChangingTheStep() {
        // Batch r2 19 shape: FILL..., ui_create CLICK, ui_created ASSERT_TEXT.
        Map<String,Object> data=addFlow("ui_create");var before=browser(data);
        var events=capture(()->{
            String once=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper);
            var expected=new ArrayList<Map<String,Object>>();before.forEach(step->expected.add(new LinkedHashMap<>(step)));expected.get(1).put("id","ui_add");
            assertEquals(expected,browser(mapper.readValue(once,Map.class)));
            assertEquals(once,ContractAcceptanceCompiler.completeCollections(once,mapper));
        });
        assertEquals(List.of("planner_browser_add_id_aligned from=ui_create to=ui_add"),
                events.stream().filter(event->event.startsWith("planner_browser_add")).toList());
        // Batch r1 16 shape: a suffixed add click already assigned to a unit.
        Map<String,Object> expense=addFlow("ui_add_expense");units(expense).getLast().put("acceptanceIds",List.of("ui_fill_title","ui_add_expense","ui_created"));
        Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(expense),mapper),Map.class);
        assertEquals("ui_add",browser(compiled).get(1).get("id"));
        assertEquals(List.of("ui_fill_title","ui_add","ui_created"),((List<?>)units(compiled).getLast().get("acceptanceIds")).subList(0,3));
        assertFalse(((List<?>)units(compiled).getLast().get("acceptanceIds")).contains("ui_add_expense"));
    }

    @Test void presentAmbiguousUnpreparedOrUnassertedAddClicksAreNotRenamed() {
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        cases.add(data->browser(data).add(action("ui_add","CLICK","button.secondary",null)));
        cases.add(data->units(data).getLast().put("acceptanceIds",List.of("ui_add")));
        cases.add(data->{browser(data).get(1).put("id","ui_add_note");browser(data).add(action("ui_add_tag","CLICK","button[data-action='tag']",null));});
        cases.add(data->browser(data).get(1).put("kind","ASSERT_EXISTS"));
        cases.add(data->browser(data).add(2,action("ui_confirm","CLICK","button[data-action='confirm']",null)));
        cases.add(data->browser(data).add(2,action("ui_pick","SELECT","select[name='tag']","work")));
        cases.add(data->browser(data).get(2).put("id","ui_shown"));
        cases.add(data->{browser(data).get(2).put("kind","ASSERT_COUNT");browser(data).get(2).put("value","1");});
        cases.add(data->browser(data).add(0,browser(data).remove(2)));
        cases.add(data->browser(data).removeFirst());
        cases.add(data->browser(data).get(1).put("id","ui_created_note"));
        cases.add(data->data.put("stack","VITE"));
        for(var mutation:cases){
            Map<String,Object> data=addFlow("ui_create");mutation.accept(data);
            var ids=browser(data).stream().map(step->step.get("id")).toList();
            var events=capture(()->{
                Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
                assertEquals(ids,browser(compiled).stream().map(step->step.get("id")).toList());
            });
            assertTrue(events.stream().noneMatch(event->event.startsWith("planner_browser_add")),ids.toString());
        }
    }

    @Test void preparedEditSubmitBeforeUiEditedBecomesUiEditWhileTheOpeningClickKeepsItsId() {
        // Batch r3 18 shape: ui_create_post_submit, ui_created, ui_edit_post CLICK, FILLs, ui_edit_post_submit CLICK, ui_edited.
        Map<String,Object> data=editFlow("ui_edit_post","ui_edit_post_submit");browser(data).get(1).put("id","ui_create_post_submit");
        browser(data).add(5,action("ui_edit_post_content","FILL","textarea[name='editContent']","Edited body"));
        units(data).getLast().put("acceptanceIds",List.of("ui_edit_post","ui_edit_post_submit","ui_edited"));var before=browser(data);
        var events=capture(()->{
            String once=ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper);
            Map<String,Object> compiled=mapper.readValue(once,Map.class);
            var expected=new ArrayList<Map<String,Object>>();before.forEach(step->expected.add(new LinkedHashMap<>(step)));
            expected.get(1).put("id","ui_add");expected.get(6).put("id","ui_edit");
            assertEquals(expected,browser(compiled));
            assertEquals(List.of("ui_edit_post","ui_edit","ui_edited"),((List<?>)units(compiled).getLast().get("acceptanceIds")).subList(0,3));
            assertEquals(once,ContractAcceptanceCompiler.completeCollections(once,mapper));
        });
        assertEquals(List.of("planner_browser_add_id_aligned from=ui_create_post_submit to=ui_add",
                "planner_browser_edit_id_aligned from=ui_edit_post_submit to=ui_edit"),
                events.stream().filter(event->event.startsWith("planner_browser_")).toList());
    }

    @Test void presentAmbiguousUnpreparedOrUnassertedEditClicksAreNotRenamed() {
        var cases=new ArrayList<java.util.function.Consumer<Map<String,Object>>>();
        cases.add(data->browser(data).add(action("ui_edit","CLICK","button.secondary",null)));
        cases.add(data->units(data).getLast().put("acceptanceIds",List.of("ui_edit")));
        cases.add(data->browser(data).add(5,action("ui_edit_confirm","CLICK","button[data-action='confirm']",null)));
        cases.add(data->browser(data).remove(4));
        cases.add(data->browser(data).remove(5));
        cases.add(data->browser(data).get(5).put("id","ui_save"));
        cases.add(data->browser(data).add(6,action("ui_confirm","CLICK","button[data-action='confirm']",null)));
        cases.add(data->browser(data).add(6,action("ui_pick","SELECT","select[name='tag']","work")));
        cases.add(data->{browser(data).get(6).put("kind","ASSERT_COUNT");browser(data).get(6).put("value","1");});
        cases.add(data->browser(data).get(6).put("id","ui_edited_post"));
        cases.add(data->browser(data).get(2).put("id","ui_shown"));
        cases.add(data->browser(data).add(0,browser(data).remove(6)));
        cases.add(data->data.put("stack","VITE"));
        for(var mutation:cases){
            Map<String,Object> data=editFlow("ui_edit_post","ui_edit_post_submit");mutation.accept(data);
            var ids=browser(data).stream().map(step->step.get("id")).toList();
            var events=capture(()->{
                Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
                assertEquals(ids,browser(compiled).stream().map(step->step.get("id")).toList());
            });
            assertTrue(events.stream().noneMatch(event->event.startsWith("planner_browser_edit")),ids.toString());
        }
    }

    @Test void theOnlyEditRoutesOwnerAlsoReceivesTheEditFillsAndSubmitButTheRootOwnerKeepsThem(){
        // Live E2E 2026-10-27: every browser check went to the "/" owner, so the edit page never rendered editTitle/save.
        for(String[] editors:List.of(new String[]{"editor"},new String[]{"editor","/edit/:id"},new String[]{"editor","other"},new String[]{"ui"})){
            Map<String,Object> data=editFlow("ui_enter_edit","ui_edit");
            var routes=(List<Map<String,Object>>)data.get("routes");
            boolean prefix=editors.length==2&&editors[1].startsWith("/");
            for(int i=0;i<(prefix?1:editors.length);i++){
                String owner=editors[i],file="client/src/pages/Edit"+i+".tsx";
                if(!owner.equals("ui"))units(data).add(new LinkedHashMap<>(Map.of("id",owner,"objective","Edit","ownedPaths",List.of(file),"imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));
                routes.add(Map.of("path",prefix?editors[1]:"/items/:id"+(i==0?"":"/v"+i)+"/edit","componentPath",owner.equals("ui")?"client/src/App.tsx":file,"owner",owner));
            }
            if(prefix)editors=new String[]{"editor"};
            Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            var byId=new HashMap<Object,List<?>>();units(compiled).forEach(unit->byId.put(unit.get("id"),(List<?>)unit.get("acceptanceIds")));
            assertTrue(byId.get("ui").containsAll(List.of("ui_edit_fill_title","ui_edit","ui_enter_edit")));
            if(editors.length==1&&editors[0].equals("editor"))assertEquals(List.of("ui_edit_fill_title","ui_edit"),byId.get("editor"));
            else if(byId.containsKey("editor"))assertEquals(List.of(),byId.get("editor"),"two edit routes are ambiguous");
        }
    }

    @Test void aSeparateCreatePageIsOpenedByALinkClickBeforeItsFillsAndItsOwnerAlsoReceivesThem(){
        // Live E2E 2026-10-28 (run 30): /tasks/create had its own page, but ui_fill ran on "/" where no add form existed.
        for(String[] creators:List.of(new String[]{"creator"},new String[]{"ui"},new String[]{"creator","other"},new String[]{"creator","opened"})){
            Map<String,Object> data=addFlow("ui_add");
            var routes=(List<Map<String,Object>>)data.get("routes");
            int count=creators.length==2&&creators[1].equals("opened")?1:creators.length;
            for(int i=0;i<count;i++){
                String owner=creators[i],file="client/src/pages/Create"+i+".tsx";
                if(!owner.equals("ui"))units(data).add(new LinkedHashMap<>(Map.of("id",owner,"objective","Create","ownedPaths",List.of(file),"imports",List.of(),"dependsOn",List.of(),"acceptanceIds",List.of())));
                routes.add(Map.of("path",i==0?"/items/new":"/notes/create","componentPath",owner.equals("ui")?"client/src/App.tsx":file,"owner",owner));
            }
            if(count<creators.length)browser(data).add(0,action("ui_new","CLICK","a[href='#/items/new']",null));
            Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),Map.class);
            var ids=browser(compiled).stream().map(step->step.get("id")).toList();
            var byId=new HashMap<Object,List<?>>();units(compiled).forEach(unit->byId.put(unit.get("id"),(List<?>)unit.get("acceptanceIds")));
            if(creators.length==1&&creators[0].equals("creator")){
                assertEquals(List.of("ui_open_create","ui_fill_title","ui_add"),ids.subList(0,3));
                assertEquals("a[href='#/items/new']",browser(compiled).getFirst().get("selector"));
                assertTrue(byId.get("ui").containsAll(List.of("ui_open_create","ui_fill_title","ui_add")));
                assertEquals(List.of("ui_fill_title","ui_add"),byId.get("creator"));
            }else{
                assertFalse(ids.contains("ui_open_create"),String.join(",",creators));
                if(byId.containsKey("creator"))assertEquals(List.of(),byId.get("creator"));
            }
        }
    }

    Map<String,Object> editFlow(String openId,String submitId){
        Map<String,Object> data=addFlow("ui_add");browser(data).get(3).put("id",openId);browser(data).get(5).put("id",submitId);return data;
    }

    @Test void approvedSearchAndFilterFeaturesGetRequiredStepsOnTheList(){
        // 2026-10-29 review: search and filter were approved, never checked, and silently left out of a passing build.
        Map<String,Object> data=addFlow("ui_add");
        int delete=browser(data).size()-2;
        browser(data).add(delete,action("ui_complete","CLICK","[data-post] input[type='checkbox']",null));
        browser(data).add(delete+1,action("ui_completed","ASSERT_COUNT","[data-post] input[type='checkbox']:checked","1"));
        String approved="{\"requestedScope\":{\"features\":[{\"id\":\"post_list\",\"kind\":\"LOCAL_CRUD\"},{\"id\":\"post_search\",\"kind\":\"SEARCH\"},{\"id\":\"status_filter\",\"kind\":\"FILTER\"}]}}";
        Map<String,Object> compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper,"Build posts",approved),Map.class);
        var ids=browser(compiled).stream().map(step->(String)step.get("id")).toList();
        int created=ids.indexOf("ui_created"),completed=ids.indexOf("ui_completed");
        assertEquals(List.of("ui_search_none","ui_search_empty","ui_search_match","ui_search_found","ui_search_clear"),ids.subList(created+1,created+6));
        assertEquals(List.of("ui_filter_active","ui_filter_hidden","ui_filter_all","ui_filter_shown"),ids.subList(completed+1,completed+5));
        var match=browser(compiled).get(created+3);
        assertEquals("input[name='search']",match.get("selector"));assertEquals("Fixture post",match.get("value"));
        assertEquals("[data-post]",browser(compiled).get(created+4).get("selector"));
        assertEquals("",browser(compiled).get(created+5).get("value"),"the query is cleared before the edit flow");
        var owner=units(compiled).stream().filter(unit->((List<?>)unit.get("acceptanceIds")).contains("ui_created")).findFirst().orElseThrow();
        assertTrue(((List<?>)owner.get("acceptanceIds")).containsAll(List.of("ui_search_found","ui_filter_shown")),"the list's owner renders them");
        mapper.readValue(mapper.writeValueAsString(compiled),BuildContract.class);

        String none="{\"requestedScope\":{\"features\":[{\"id\":\"post_list\",\"kind\":\"LOCAL_CRUD\"}]}}";
        Map<String,Object> plain=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper,"Build posts",none),Map.class);
        assertTrue(browser(plain).stream().noneMatch(step->String.valueOf(step.get("id")).matches("ui_(search|filter)_.*")));
        while(browser(data).size()<20)browser(data).add(0,action("ui_extra_"+browser(data).size(),"ASSERT_EXISTS","main",null));
        var logs=capture(()->ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper,"Build posts",approved));
        assertTrue(logs.stream().anyMatch(line->line.contains("planner_feature_checks_skipped feature=search")),logs.toString());
    }

    @Test void aSloppyThemeIsNormalizedInsteadOfRejectingThePlan(){
        Map<String,Object> data=addFlow("ui_add");
        data.put("theme",new LinkedHashMap<>(Map.of("background","white","foreground","rgb(17, 24, 39)","accent","#3B82F6","radius",8,"spacing","1rem","font","Inter, sans-serif","shadow","soft")));
        var compiled=mapper.readValue(ContractAcceptanceCompiler.completeCollections(mapper.writeValueAsString(data),mapper),BuildContract.class);
        assertEquals(Map.of("background","#ffffff","foreground","#111827","accent","#3b82f6","radius","8px","spacing","16px","font","system"),compiled.theme());
    }

    Map<String,Object> addFlow(String addId){
        Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(ContractFixtures.mern()),Map.class);
        ((Map<String,Object>)data.get("acceptance")).put("browser",new ArrayList<>(List.of(
                action("ui_fill_title","FILL","input[name='title']","Fixture post"),action(addId,"CLICK","button[data-action='create']",null),
                action("ui_created","ASSERT_TEXT","[data-post]","Fixture post"),action("ui_enter_edit","CLICK","button[data-action='edit']",null),
                action("ui_edit_fill_title","FILL","input[name='editTitle']","Edited post"),action("ui_edit","CLICK","button[data-action='save']",null),
                action("ui_edited","ASSERT_TEXT","[data-post]","Edited post"),action("ui_reload","RELOAD",null,null),
                action("ui_persisted","ASSERT_TEXT","[data-post]","Edited post"),action("ui_delete","CLICK","button[data-action='delete']",null),
                action("ui_deleted","ASSERT_COUNT","[data-post]","0"))));
        units(data).getLast().put("acceptanceIds",List.of());
        return data;
    }
    static Map<String,Object> action(String id,String kind,String selector,String value){
        var action=new LinkedHashMap<String,Object>();action.put("id",id);action.put("kind",kind);action.put("selector",selector);action.put("value",value);return action;
    }
    private static List<Map<String,Object>> browser(Map<String,Object> data){return (List<Map<String,Object>>)((Map<String,Object>)data.get("acceptance")).get("browser");}

    private static List<String> capture(Runnable action){
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ContractAcceptanceCompiler.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        var level=logger.getLevel();appender.start();logger.addAppender(appender);logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try{action.run();}finally{logger.detachAppender(appender);logger.setLevel(level);}
        return appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
    }

    private Map<String,Object> missingRecordRead(BuildContract original){
        Map<String,Object> data=mapper.readValue(mapper.writeValueAsString(original),Map.class);
        api(data).removeIf(api->"GET".equals(api.get("method")));
        units(data).getFirst().put("acceptanceIds",List.of());
        return data;
    }
    private static Map<String,Object> limits(int modelCalls){return Map.of("modelCalls",modelCalls,"toolCalls",40,"outputTokens",8192,"timeoutMs",60000);}
    private static List<Map<String,Object>> units(Map<String,Object> data){return (List<Map<String,Object>>)data.get("units");}
    private static List<Map<String,Object>> api(Map<String,Object> data){return (List<Map<String,Object>>)data.get("api");}
}
