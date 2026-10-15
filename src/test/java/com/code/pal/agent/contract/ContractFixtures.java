package com.code.pal.agent.contract;
import com.code.pal.agent.validation.BehaviorCriteria;
import com.code.pal.project.capability.BuildDecisions;
import java.util.*;
public final class ContractFixtures {
    public static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000071");
    public static BuildContract staticSite(){return BuildContractTest.valid(List.of(BuildContractTest.unit("page","index.html",List.of())));}
    public static BuildContract vite(){return new BuildContract(1,ID,BuildContract.Stack.VITE,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,limits(),theme(),List.of(new BuildContract.Route("/","src/App.tsx","ui")),List.of(),List.of(),List.of(),List.of(),Set.of(),List.of(unit("ui","src/App.tsx",List.of(),List.of("ui"))),new BehaviorCriteria(1,browser(),List.of(),false,false));}
    public static BuildContract mern() {
        var value=new BuildContract.Field("value",BuildContract.Kind.STRING,false);
        var item=new BuildContract.DataType("Item",List.of(new BuildContract.Field("id",BuildContract.Kind.STRING,false),value));
        var create=new BuildContract.DataType("ItemInput",List.of(value));
        var api=new ArrayList<BuildContract.Api>();
        for(String method:List.of("POST","GET","PUT","DELETE"))api.add(new BuildContract.Api("item"+method,method,method.equals("POST")?"/api/items":"/api/items/:id",Set.of("POST","PUT").contains(method)?"ItemInput":null,"Item","data","server/src/items.ts","itemsRouter","/api/items",false));
        var checks=List.of(step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",null,200,null,null,null,null),
                step("create",BehaviorCriteria.ApiKind.CREATE,"POST","/api/items","{\"value\":\"one\"}",201,"itemId","/id",null,null),
                step("read",BehaviorCriteria.ApiKind.READ,"GET","/api/items/${itemId}",null,200,null,null,"/value","one"),
                step("update",BehaviorCriteria.ApiKind.UPDATE,"PUT","/api/items/${itemId}","{\"value\":\"two\"}",200,null,null,"/value","two"),
                step("restart",BehaviorCriteria.ApiKind.PERSISTENCE,"GET","/api/items/${itemId}",null,200,null,null,"/value","two"),
                step("delete",BehaviorCriteria.ApiKind.DELETE,"DELETE","/api/items/${itemId}",null,200,null,null,null,null),
                step("missing",BehaviorCriteria.ApiKind.NOT_FOUND,"GET","/api/items/${itemId}",null,404,null,null,null,null));
        return new BuildContract(1,ID,BuildContract.Stack.MERN,BuildDecisions.Storage.MERN,BuildDecisions.Auth.NONE,limits(),theme(),
                List.of(new BuildContract.Route("/","client/src/App.tsx","ui")),List.of(item,create),List.of(new BuildContract.DataType("Item",List.of(value))),api,List.of(),Set.of("MONGODB_URI"),
                List.of(unit("data","server/src/items.ts",List.of(),checks.stream().map(BehaviorCriteria.ApiStep::id).toList()),unit("ui","client/src/App.tsx",List.of("data"),List.of("ui"))),new BehaviorCriteria(1,browser(),checks,false,true));
    }
    private static BuildContract.Unit unit(String id,String file,List<String> dependencies,List<String> checks){return new BuildContract.Unit(id,"Implement "+id,List.of(file),List.of(),dependencies,checks);}
    public static BuildContract accounts() {
        var c=mern();var checks=new ArrayList<BehaviorCriteria.ApiStep>();checks.add(c.acceptance().api().getFirst());
        String credentials="{\"email\":\"${fixtureEmail}\",\"password\":\"${fixturePassword}\"}";
        checks.add(step("signup",BehaviorCriteria.ApiKind.AUTH_SIGNUP,"POST","/api/auth/signup",credentials,201,"signupToken","/token",null,null));
        checks.add(step("login",BehaviorCriteria.ApiKind.AUTH_LOGIN,"POST","/api/auth/login",credentials,200,"sessionToken","/token",null,null));
        checks.add(step("noToken",BehaviorCriteria.ApiKind.AUTH_REJECT,"GET","/api/items",null,401,null,null,null,null));
        checks.add(step("wrongPassword",BehaviorCriteria.ApiKind.AUTH_REJECT,"POST","/api/auth/login","{\"email\":\"${fixtureEmail}\",\"password\":\"${invalidToken}\"}",401,null,null,null,null));
        checks.add(new BehaviorCriteria.ApiStep("invalid",BehaviorCriteria.ApiKind.AUTH_INVALID,"GET","/api/items",null,401,"invalidToken",null,null,null,null));
        c.acceptance().api().stream().skip(1).forEach(a->checks.add(new BehaviorCriteria.ApiStep(a.id(),a.kind(),a.method(),a.path(),a.body(),a.status(),"sessionToken",a.captureVariable(),a.capturePointer(),a.assertPointer(),a.assertValue())));
        var apis=c.api().stream().map(a->new BuildContract.Api(a.id(),a.method(),a.path(),a.requestType(),a.responseType(),a.owner(),a.routerPath(),a.routerExport(),a.basePath(),true)).toList();
        return new BuildContract(c.schemaVersion(),c.id(),c.stack(),c.storage(),BuildDecisions.Auth.ACCOUNTS,c.limits(),c.theme(),c.routes(),c.types(),c.models(),apis,c.assets(),c.envNames(),
                List.of(unit("data","server/src/items.ts",List.of(),checks.stream().map(BehaviorCriteria.ApiStep::id).toList()),c.units().getLast()),new BehaviorCriteria(1,c.acceptance().browser(),checks,true,true));
    }
    public static BuildContract.Limits limits(){return new BuildContract.Limits(10,40,8192,60000);}
    public static Map<String,String> theme(){return Map.of("accent","#123456","background","#ffffff","foreground","#111111");}
    private static List<BehaviorCriteria.BrowserAction> browser(){return List.of(new BehaviorCriteria.BrowserAction("ui",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#root","Items"));}
    private static BehaviorCriteria.ApiStep step(String id,BehaviorCriteria.ApiKind kind,String method,String path,String body,int status,String capture,String pointer,String assertPointer,String expected){return new BehaviorCriteria.ApiStep(id,kind,method,path,body,status,null,capture,pointer,assertPointer,expected);}
}
