package com.code.pal.agent.contract;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildContractTest {
    @org.junit.jupiter.api.Test void rootApiMountIsValidButForeignAndMismatchedMountsAreRejected() {
        var api=new BuildContract.Api("list","GET","/api/items",null,"unknown","data","server/src/items.ts","itemsRouter","/api/",false);
        org.junit.jupiter.api.Assertions.assertEquals("/api",api.basePath());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->new BuildContract.Api("list","GET","/api/items",null,"unknown","data","server/src/items.ts","itemsRouter","/other",false));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->new BuildContract.Api("list","GET","/api/items",null,"unknown","data","server/src/items.ts","itemsRouter","/api/other",false));
    }
    static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000071");
    static BuildContract valid(List<BuildContract.Unit> units){return new BuildContract(1,ID,BuildContract.Stack.STATIC,BuildDecisions.Storage.NONE,BuildDecisions.Auth.NONE,
            new BuildContract.Limits(10,20,8192,60000),Map.of("accent","#123456"),List.of(),List.of(),List.of(),List.of(),List.of(),Set.of(),units,
            new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("result",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#result","5")),List.of(),false,false));}
    static BuildContract.Unit unit(String id,String file,List<String> depends){return new BuildContract.Unit(id,"Implement "+id,List.of(file),List.of(),depends,List.of("result"));}
    @Test void deterministicDagProducesOnlyReadyWaves() {
        var c=valid(List.of(unit("page","index.html",List.of("data")),unit("data","data.js",List.of()),unit("style","style.css",List.of())));
        assertEquals(List.of(List.of("data","style"),List.of("page")),c.waves().stream().map(w->w.stream().map(BuildContract.Unit::id).toList()).toList());
    }
    @Test void cyclesAndUnknownDependenciesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->valid(List.of(unit("a","a.js",List.of("b")),unit("b","b.js",List.of("a")))));
        assertThrows(IllegalArgumentException.class,()->valid(List.of(unit("a","a.js",List.of("missing")))));
    }
    @Test void overlappingPortableAndAncestorOwnershipAreRejected() {
        for(var files:List.of(List.of("src/A.ts","src/a.ts"),List.of("src/item","src/item/x.ts")))
            assertThrows(IllegalArgumentException.class,()->valid(List.of(unit("a",files.getFirst(),List.of()),unit("b",files.getLast(),List.of()))));
    }
    @Test void secretsAliasesAndCoordinatorFilesAreRejected() {
        for(String path:List.of("../escape.ts","src/../a.ts","src//a.ts",".env","RENDRA.md","node_modules/a.js","package.json","rendra/"+ID+"/types.ts"))
            assertThrows(IllegalArgumentException.class,()->valid(List.of(unit("a",path,List.of()))),path);
    }
    @Test void crossUnitImportsNeedAnExplicitDependency() {
        var a=new BuildContract.Unit("a","Read b",List.of("a.ts"),List.of("b.ts"),List.of(),List.of("result"));
        assertThrows(IllegalArgumentException.class,()->valid(List.of(a,unit("b","b.ts",List.of()))));
    }
    @Test void everyCriterionHasAResponsibleUnitAndCapsCannotGrow() {
        assertThrows(IllegalArgumentException.class,()->valid(List.of(new BuildContract.Unit("a","Implement",List.of("a.js"),List.of(),List.of(),List.of()))));
        assertFalse(new BuildContract.Limits(11,20,8192,60000).within(valid(List.of(unit("a","a.js",List.of()))).limits()));
    }
    @Test void staticViteAndMernRoundTripThroughTheSameValidatedSchema() {
        var mapper=new tools.jackson.databind.ObjectMapper();
        for(var contract:List.of(ContractFixtures.staticSite(),ContractFixtures.vite(),ContractFixtures.mern()))
            assertEquals(contract,mapper.readValue(mapper.writeValueAsString(contract),BuildContract.class));
    }
    @Test void anUntestedApiCannotHideBehindOtherPassingChecks() {
        var c=ContractFixtures.mern();var api=new ArrayList<>(c.api());api.add(new BuildContract.Api("untested","GET","/api/items/untested",null,"Item","data","server/src/items.ts","itemsRouter","/api/items",false));
        assertThrows(IllegalArgumentException.class,()->new BuildContract(c.schemaVersion(),c.id(),c.stack(),c.storage(),c.auth(),c.limits(),c.theme(),c.routes(),c.types(),c.models(),api,c.assets(),c.envNames(),c.units(),c.acceptance()));
    }
}
