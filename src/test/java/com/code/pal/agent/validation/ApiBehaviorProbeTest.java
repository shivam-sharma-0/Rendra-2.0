package com.code.pal.agent.validation;

import com.sun.net.httpserver.HttpServer;
import com.code.pal.agent.workspace.SandboxPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ApiBehaviorProbeTest {
    @Test void capturedIdentityAndPersistenceRestartAreExercisedOnTheCandidateOnly() throws Exception {
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var restarted=new AtomicInteger();var requests=new ArrayList<String>();
        http.createContext("/api/",exchange->{requests.add(exchange.getRequestMethod()+" "+exchange.getRequestURI());String body=exchange.getRequestURI().getPath().equals("/api/create")?"{\"id\":\"fixture-record\"}":"{\"value\":\"persisted\"}";exchange.sendResponseHeaders(200,body.length());exchange.getResponseBody().write(body.getBytes());exchange.close();});http.start();
        try {
            var health=BehaviorCriteriaTest.step("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",200);
            var create=new BehaviorCriteria.ApiStep("create",BehaviorCriteria.ApiKind.CREATE,"POST","/api/create","{}",200,null,"recordId","/id",null,null);
            var persistence=new BehaviorCriteria.ApiStep("persist",BehaviorCriteria.ApiKind.PERSISTENCE,"GET","/api/items/${recordId}",null,200,null,null,null,"/value","persisted");
            var criteria=new BehaviorCriteria(1,List.of(),List.of(health,create,persistence),false,false);
            var result=new ApiBehaviorProbe(new ObjectMapper()).run(criteria,()->new SandboxPort.ServerEndpoint("http://127.0.0.1:"+http.getAddress().getPort(),Map.of()),restarted::incrementAndGet);
            assertTrue(result.stream().allMatch(c->c.outcome()==ValidationCheck.Outcome.PASS));assertEquals(1,restarted.get());assertTrue(requests.contains("GET /api/items/fixture-record"));
        } finally{http.stop(0);}
    }
    @Test void failuresAndMissingAssertionsDoNotPassOrExposeRawResponses() throws Exception {
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/api/",exchange->{String body="{\"private\":\"RAW_SECRET_RESPONSE\"}";exchange.sendResponseHeaders(200,body.length());exchange.getResponseBody().write(body.getBytes());exchange.close();});http.start();
        try {
            var health=new BehaviorCriteria.ApiStep("health",BehaviorCriteria.ApiKind.HEALTH,"GET","/api/health",null,200,null,null,null,"/missing","");
            var later=BehaviorCriteriaTest.step("read",BehaviorCriteria.ApiKind.READ,"GET","/api/items",200);
            var result=new ApiBehaviorProbe(new ObjectMapper()).run(new BehaviorCriteria(1,List.of(),List.of(health,later),false,false),()->new SandboxPort.ServerEndpoint("http://127.0.0.1:"+http.getAddress().getPort(),Map.of()),()->{});
            assertEquals(ValidationCheck.Outcome.FAIL,result.getFirst().outcome());assertEquals(ValidationCheck.Outcome.SKIPPED,result.getLast().outcome());assertFalse(result.toString().contains("RAW_SECRET_RESPONSE"));
            assertEquals(ValidationCheck.SkipCause.PREREQUISITE_FAILED,result.getLast().skipCause());assertFalse(result.getLast().unavailable());assertTrue(result.getLast().blocksSuccess());
        }finally{http.stop(0);}
    }
}
