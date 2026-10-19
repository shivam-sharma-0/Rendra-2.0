package com.code.pal.agent.contract;

import com.code.pal.agent.validation.BehaviorCriteria;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractAcceptanceCompilerTest {
    final ObjectMapper mapper=new ObjectMapper();
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
}
