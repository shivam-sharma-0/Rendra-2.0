package com.code.pal.agent.workspace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BrowserApiRelayTest {
    @TempDir Path temporary;
    final ObjectMapper mapper=new ObjectMapper();
    HttpServer server;final AtomicInteger requests=new AtomicInteger();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/todos",exchange->{
            requests.incrementAndGet();assertEquals("candidate-only",exchange.getRequestHeaders().getFirst("x-codepal-upstream"));
            assertNull(exchange.getRequestHeaders().getFirst("cookie"));
            byte[] body=exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("content-type","application/json");
            if(body.length==0)body="[]".getBytes();exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    BrowserApiRelay.Reply send(Path directory,String path,String body) throws Exception {
        String id=UUID.randomUUID().toString().replace("-","");
        var request=new BrowserApiRelay.Request(body.isEmpty()?"GET":"POST",path,Map.of("content-type","application/json","cookie","platform-cookie"),Base64.getEncoder().encodeToString(body.getBytes()));
        Path temporary=directory.resolve(id+".tmp");Files.writeString(temporary,mapper.writeValueAsString(request));Files.move(temporary,directory.resolve(id+".request.json"));
        Path reply=directory.resolve(id+".response.json");long deadline=System.nanoTime()+5_000_000_000L;
        while(!Files.exists(reply) && System.nanoTime()<deadline)Thread.sleep(20);
        return mapper.readValue(Files.readString(reply),BrowserApiRelay.Reply.class);
    }
    SandboxPort.ServerEndpoint endpoint(){return new SandboxPort.ServerEndpoint("http://127.0.0.1:"+server.getAddress().getPort(),Map.of("x-codepal-upstream","candidate-only"));}
    @Test void forwardsCandidateJsonAndRemovesOwnedRelayFiles() throws Exception {
        Path directory=temporary.resolve("relay");
        try(var relay=new BrowserApiRelay(directory,endpoint())) {
            var reply=send(directory,"/api/todos","{\"title\":\"Test\"}");
            assertEquals(200,reply.status());assertFalse(reply.unavailable());
            assertEquals("{\"title\":\"Test\"}",new String(Base64.getDecoder().decode(reply.body())));assertEquals(1,requests.get());
        }
        assertFalse(Files.exists(directory));
    }
    @Test void rejectsExternalPlatformAndEncodedTraversalPathsWithoutSending() throws Exception {
        try(var relay=new BrowserApiRelay(temporary.resolve("relay"),endpoint())) {
            for(String path:List.of("http://127.0.0.1/api/todos","//127.0.0.1/api/todos","/api/auth/../../actuator","/api/%2e%2e/actuator","/actuator/health")) {
                var reply=send(temporary.resolve("relay"),path,"");assertEquals(503,reply.status());assertTrue(reply.unavailable());
            }
            assertEquals(0,requests.get());
        }
    }
    @Test void rejectsAnOversizedResponseWithoutDeliveringItToBrowser() throws Exception {
        server.createContext("/api/large",exchange->{exchange.sendResponseHeaders(200,BrowserApiRelay.MAX_BYTES+1);exchange.getResponseBody().write(new byte[BrowserApiRelay.MAX_BYTES+1]);exchange.close();});
        try(var relay=new BrowserApiRelay(temporary.resolve("relay"),endpoint())){assertTrue(send(temporary.resolve("relay"),"/api/large","").unavailable());}
    }
}
