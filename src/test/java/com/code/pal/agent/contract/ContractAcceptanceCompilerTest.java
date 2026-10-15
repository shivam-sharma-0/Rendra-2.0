package com.code.pal.agent.contract;

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
}
