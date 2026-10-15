package com.code.pal.agent.validation;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorCriteriaTest {
    @Test void browserAndApiUseSeparateIdentityNamespacesButDuplicatesWithinASequenceFail() {
        var browser=new BehaviorCriteria.BrowserAction("health",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"h1","Tasks");var health=step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",200);
        assertDoesNotThrow(()->new BehaviorCriteria(1,List.of(browser),List.of(health),false,false));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(browser,browser),List.of(health),false,false));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(browser),List.of(health,health),false,false));
    }
    static BehaviorCriteria.ApiStep step(String id,BehaviorCriteria.ApiKind kind,String method,String path,int status){return new BehaviorCriteria.ApiStep(id,kind,method,path,null,status,null,null,null,null,null);}
    @Test void missingAuthCrudAndExternalRoutesCannotWeakenAcceptance() {
        var health=step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",200);
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(),List.of(health),true,false));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(),List.of(health),false,true));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(),List.of(step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","https://external.invalid/api",200)),false,false));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(),List.of(health,step("reject",BehaviorCriteria.ApiKind.AUTH_REJECT,"GET","/api/private",200)),false,false));
    }
    @Test void browserDataIsBoundedAndNeverJavascript() {
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("x",BehaviorCriteria.BrowserKind.FILL,"input",null)),List.of(),false,false));
        assertThrows(IllegalArgumentException.class,()->new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("x",BehaviorCriteria.BrowserKind.ASSERT_COUNT,"input","NaN")),List.of(),false,false));
        assertDoesNotThrow(()->new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("x",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#result","4")),List.of(),false,false));
    }
}
