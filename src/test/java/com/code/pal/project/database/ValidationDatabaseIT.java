package com.code.pal.project.database;

import com.mongodb.client.*;
import com.mongodb.MongoException;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import com.code.pal.agent.validation.*;
import com.code.pal.agent.workspace.SandboxPort;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;

@EnabledIfSystemProperty(named="codepal.behavior.it",matches="true")
class ValidationDatabaseIT {
    private MongoClient admin;
    private GeneratedDatabaseService service;
    private String address;
    @BeforeEach void open() {
        String owner=System.getProperty("codex.behavior.project");int port=Integer.parseInt(System.getProperty("codex.behavior.mongo.port","0"));
        assertTrue(owner!=null && owner.matches("codepal-codex-behavior-it-[a-f0-9]{12}"));assertTrue(port>1024 && port<65536 && port!=27017);
        address="127.0.0.1:"+port;
        admin=MongoClients.create("mongodb://codexFixtureAdmin:codex-test-only-not-production@"+address+"/?authSource=admin&serverSelectionTimeoutMS=5000&connectTimeoutMS=3000");
        var marker=admin.getDatabase("admin").getCollection("_codex_fixture").find(new Document("_id",owner)).first();
        assertNotNull(marker,"Refuse any database without the exact disposable runner marker");assertEquals("behavior-verification",marker.getString("purpose"));
        service=new GeneratedDatabaseService(()->admin);
    }
    @AfterEach void close(){if(admin!=null)admin.close();}
    @Test void parallelScratchCredentialsAndDataAreIndependentAndLiveDataRemainUntouched() {
        UUID project=UUID.randomUUID();String live="codepal_"+project.toString().replace('-','_');
        admin.getDatabase(live).getCollection("accepted").insertOne(new Document("value","live-baseline"));
        var a=service.openValidation(project,UUID.randomUUID(),address);var b=service.openValidation(project,UUID.randomUUID(),address);
        var owner=admin.getDatabase(a.name()).getCollection("_codepal_validation_lease").find(new Document("_id","lease")).first();
        assertNotNull(owner);assertEquals(project.toString(),owner.getString("projectId"));assertEquals(a.runId().toString(),owner.getString("runId"));
        assertEquals("REQUIRE_PROVEN_PROCESS_STOP",owner.getString("retention"));
        try(var first=MongoClients.create(a.uri());var second=MongoClients.create(b.uri())) {
            assertNotEquals(a.name(),b.name());assertTrue(a.name().length()<64);assertFalse(a.toString().contains("mongodb://"));
            first.getDatabase(a.name()).getCollection("items").insertOne(new Document("value","candidate"));
            assertEquals(1,first.getDatabase(a.name()).getCollection("items").countDocuments());assertEquals(0,second.getDatabase(b.name()).getCollection("items").countDocuments());
            assertThrows(MongoException.class,()->first.getDatabase(b.name()).getCollection("items").countDocuments());
            assertEquals("live-baseline",admin.getDatabase(live).getCollection("accepted").find().first().getString("value"));
        }finally{service.closeValidation(a);service.closeValidation(b);}
        assertEquals(0,admin.getDatabase(a.name()).listCollectionNames().into(new java.util.ArrayList<>()).size());
        assertEquals(1,admin.getDatabase(live).getCollection("accepted").countDocuments());
        assertThrows(IllegalArgumentException.class,()->service.closeValidation(new GeneratedDatabaseService.ValidationDatabase(project,UUID.randomUUID(),live,"not-a-lease")));
    }
    @Test void reopeningAClientKeepsCandidateDataUntilExplicitCleanup() {
        var lease=service.openValidation(UUID.randomUUID(),UUID.randomUUID(),address);
        try {
            try(var client=MongoClients.create(lease.uri())){client.getDatabase(lease.name()).getCollection("items").insertOne(new Document("_id","item").append("value","persisted"));}
            try(var client=MongoClients.create(lease.uri())){assertEquals("persisted",client.getDatabase(lease.name()).getCollection("items").find(new Document("_id","item")).first().getString("value"));}
        }finally{service.closeValidation(lease);}
        assertEquals(0,admin.getDatabase(lease.name()).listCollectionNames().into(new java.util.ArrayList<>()).size());
    }
    @Test void candidateAuthCrudAndRestartPersistenceUseDisposableDataAndDetectLoss() throws Exception {
        UUID project=UUID.randomUUID();String live="codepal_"+project.toString().replace('-','_');
        admin.getDatabase(live).getCollection("accepted").insertOne(new Document("_id","baseline"));
        for(boolean losesData:List.of(false,true)) {
            var lease=service.openValidation(project,UUID.randomUUID(),address);
            try(var candidate=new CandidateApiFixture(lease,losesData)) {
                var result=new ApiBehaviorProbe(new ObjectMapper()).run(recipe(),candidate::endpoint,candidate::restart);
                if(!losesData)assertTrue(result.stream().allMatch(c->c.outcome()==ValidationCheck.Outcome.PASS),result.toString());
                else {assertEquals(ValidationCheck.Outcome.FAIL,result.stream().filter(c->c.id().equals("API_persist")).findFirst().orElseThrow().outcome());
                    assertEquals(ValidationCheck.Outcome.SKIPPED,result.stream().filter(c->c.id().equals("API_delete")).findFirst().orElseThrow().outcome());}
                assertEquals(1,admin.getDatabase(live).getCollection("accepted").countDocuments());
            }finally{service.closeValidation(lease);}
        }
    }
    private static BehaviorCriteria recipe() {
        return new BehaviorCriteria(1,List.of(),List.of(
            step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",null,200,null,null,null,"/ok","true"),
            step("reject",BehaviorCriteria.ApiKind.AUTH_REJECT,"GET","/api/items",null,401,null,null,null,null,null),
            step("signup",BehaviorCriteria.ApiKind.AUTH_SIGNUP,"POST","/api/signup","{\"email\":\"${fixtureEmail}\",\"password\":\"${fixturePassword}\"}",201,null,null,null,null,null),
            step("login",BehaviorCriteria.ApiKind.AUTH_LOGIN,"POST","/api/login","{\"email\":\"${fixtureEmail}\",\"password\":\"${fixturePassword}\"}",200,null,"token","/token",null,null),
            step("invalid",BehaviorCriteria.ApiKind.AUTH_INVALID,"GET","/api/items",null,401,"invalidToken",null,null,null,null),
            step("create",BehaviorCriteria.ApiKind.CREATE,"POST","/api/items","{\"value\":\"before\"}",201,"token","item","/id",null,null),
            step("read",BehaviorCriteria.ApiKind.READ,"GET","/api/items/${item}",null,200,"token",null,null,"/value","before"),
            step("update",BehaviorCriteria.ApiKind.UPDATE,"PATCH","/api/items/${item}","{\"value\":\"after\"}",200,"token",null,null,"/value","after"),
            step("persist",BehaviorCriteria.ApiKind.PERSISTENCE,"GET","/api/items/${item}",null,200,"token",null,null,"/value","after"),
            step("delete",BehaviorCriteria.ApiKind.DELETE,"DELETE","/api/items/${item}",null,204,"token",null,null,null,null),
            step("missing",BehaviorCriteria.ApiKind.NOT_FOUND,"GET","/api/items/${item}",null,404,"token",null,null,null,null)
        ),true,true);
    }
    private static BehaviorCriteria.ApiStep step(String id,BehaviorCriteria.ApiKind kind,String method,String path,String body,int status,String token,String capture,String capturePath,String assertPath,String expected) {
        return new BehaviorCriteria.ApiStep(id,kind,method,path,body,status,token,capture,capturePath,assertPath,expected);
    }
    /** HTTP adapter fixture with real Mongo persistence; not a generated Express app/security certification. */
    private static final class CandidateApiFixture implements AutoCloseable {
        private final GeneratedDatabaseService.ValidationDatabase lease;private final boolean losesData;private final ObjectMapper mapper=new ObjectMapper();
        private MongoClient client;private HttpServer http;private int port;
        CandidateApiFixture(GeneratedDatabaseService.ValidationDatabase lease,boolean losesData) throws Exception {this.lease=lease;this.losesData=losesData;start();}
        private void start() throws Exception {
            client=MongoClients.create(lease.uri());http=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
            http.createContext("/api/",exchange->{try {
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                var db=client.getDatabase(lease.name());var users=db.getCollection("users");var items=db.getCollection("items");
                if(path.equals("/api/health")){reply(exchange,200,Map.of("ok",true));return;}
                if(path.equals("/api/signup") || path.equals("/api/login")) {
                    var body=mapper.readTree(new String(exchange.getRequestBody().readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8));
                    String email=body.path("email").asString(),password=body.path("password").asString();
                    if(path.endsWith("signup")){users.insertOne(new Document("_id",email).append("fixturePassword",password));reply(exchange,201,Map.of("created",true));return;}
                    var user=users.find(new Document("_id",email).append("fixturePassword",password)).first();
                    if(user==null){reply(exchange,401,Map.of());return;}
                    String token=UUID.randomUUID().toString();users.updateOne(new Document("_id",email),new Document("$set",new Document("token",token)));reply(exchange,200,Map.of("token",token));return;
                }
                String auth=exchange.getRequestHeaders().getFirst("Authorization");
                if(auth==null || !auth.startsWith("Bearer ") || users.find(new Document("token",auth.substring(7))).first()==null){reply(exchange,401,Map.of());return;}
                if(path.equals("/api/items") && method.equals("POST")) {
                    String value=mapper.readTree(new String(exchange.getRequestBody().readAllBytes())).path("value").asString();String id=UUID.randomUUID().toString();
                    items.insertOne(new Document("_id",id).append("value",value));reply(exchange,201,Map.of("id",id,"value",value));return;
                }
                String id=path.substring(path.lastIndexOf('/')+1);var item=items.find(new Document("_id",id)).first();
                if(item==null){reply(exchange,404,Map.of());return;}
                if(method.equals("DELETE")){items.deleteOne(new Document("_id",id));reply(exchange,204,Map.of());return;}
                if(method.equals("PATCH")){String value=mapper.readTree(new String(exchange.getRequestBody().readAllBytes())).path("value").asString();items.updateOne(new Document("_id",id),new Document("$set",new Document("value",value)));reply(exchange,200,Map.of("value",value));return;}
                reply(exchange,200,Map.of("value",item.getString("value")));
            }catch(Exception failed){reply(exchange,500,Map.of());}});
            http.start();port=http.getAddress().getPort();
        }
        SandboxPort.ServerEndpoint endpoint(){return new SandboxPort.ServerEndpoint("http://127.0.0.1:"+port,Map.of());}
        void restart(){try{if(losesData)client.getDatabase(lease.name()).getCollection("items").deleteMany(new Document());http.stop(0);client.close();start();}catch(Exception failure){throw new IllegalStateException(failure);}}
        private void reply(com.sun.net.httpserver.HttpExchange exchange,int status,Object value) throws java.io.IOException {
            byte[] body=mapper.writeValueAsBytes(value);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,status==204?-1:body.length);if(status!=204)exchange.getResponseBody().write(body);exchange.close();
        }
        @Override public void close(){if(http!=null)http.stop(0);if(client!=null)client.close();}
    }
}
