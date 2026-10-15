package com.code.pal.agent.workspace;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BrowserApiRelayTest {
    @TempDir Path temporary;
    final ObjectMapper mapper=new ObjectMapper();
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final AtomicReference<URI> receivedTarget = new AtomicReference<>();
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            requests.incrementAndGet();
            receivedTarget.set(exchange.getRequestURI());
            assertEquals("candidate-only", exchange.getRequestHeaders().getFirst("x-codepal-upstream"));
            assertNull(exchange.getRequestHeaders().getFirst("cookie"));
            byte[] body = exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("content-type", "application/json");
            if (body.length == 0) {
                body = "[]".getBytes();
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
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

    @ParameterizedTest
    @ValueSource(strings = {
            "%2Fapi%2F@169.254.169.254/latest/meta-data",
            "%2fapi%2f@127.0.0.1/api/todos",
            "/api/todos%2Fother",
            "/api/todos%2fother",
            "/api/todos%5Cother",
            "/api/todos%5cother",
            "/api/todos%40other",
            "/api/%252F%2540example.invalid",
            "/api/%252e%252e/actuator",
            "/api/%00todos",
            "/api/%0Atodos",
            "/api/%7Ftodos",
            "/api/%2E%2E/actuator",
            "/api/./todos",
            "/api/../actuator",
            "/api//todos",
            "/api\\todos",
            "//example.invalid/api/todos",
            "http://example.invalid/api/todos",
            "http://candidate@example.invalid/api/todos",
            "https://example.invalid/api/todos",
            "/api/todos#fragment",
            "/api/todos%",
            "/apiculture/todos"
    })
    void rejectsUnsafePathsBeforeCallingHttpTransport(String path) throws Exception {
        HttpClient transport = mock(HttpClient.class);
        Path directory = temporary.resolve("relay");

        try (var relay = new BrowserApiRelay(directory, endpoint(), transport)) {
            BrowserApiRelay.Reply reply = send(directory, path, "");

            assertEquals(503, reply.status());
            assertTrue(reply.unavailable());
            verifyNoInteractions(transport);
            assertEquals(0, requests.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api",
            "/api/todos",
            "/api/todos/child",
            "/api/todos/caf%C3%A9",
            "/api/todos/hello%20world",
            "/api/todos/@someone",
            "/api/todos?filter=a%2Fb&email=a%40b.example",
            "/api/todos?next=https://example.invalid/path",
            "/api/todos?literal=%252F%2540%23"
    })
    void preservesValidCandidatePathsAndQueryEncoding(String path) throws Exception {
        Path directory = temporary.resolve("relay");
        var candidate = new SandboxPort.ServerEndpoint(
                endpoint().baseUrl() + "/", endpoint().headers());

        try (var relay = new BrowserApiRelay(directory, candidate)) {
            BrowserApiRelay.Reply reply = send(directory, path, "");

            assertEquals(200, reply.status());
            assertFalse(reply.unavailable());
            assertEquals(1, requests.get());
            assertEquals(URI.create(path), receivedTarget.get());
        }
    }
}
