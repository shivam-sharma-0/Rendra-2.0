package com.code.pal.agent.runtime;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpTest {
    @Test void normalAndOversizedResponsesAreBoundedBeforePublishing() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/small",x->{byte[] data="ok".getBytes();x.sendResponseHeaders(201,data.length);try(var body=x.getResponseBody()){body.write(data);}});
        server.createContext("/large",x->{x.sendResponseHeaders(200,70_000);try(var body=x.getResponseBody()){body.write(new byte[70_000]);}catch(java.io.IOException closed){}});server.start();
        try{var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+server.getAddress().getPort();
            var result=BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(base+"/small")).build(),64,Duration.ofSeconds(2));assertEquals(201,result.status());assertEquals("ok",new String(result.body()));
            assertThrows(java.io.IOException.class,()->BoundedHttp.send(client,HttpRequest.newBuilder(URI.create(base+"/large")).build(),65_536,Duration.ofSeconds(2)));
        }finally{server.stop(0);}
    }
    @Test void slowBodyCannotOutliveTheWholeResponseDeadline() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var headers=new CountDownLatch(1);
        server.createContext("/slow",x->{x.sendResponseHeaders(200,0);headers.countDown();try(var body=x.getResponseBody()){body.write(1);body.flush();Thread.sleep(1_000);body.write(2);}catch(Exception closed){}});server.start();
        try{long began=System.nanoTime();assertThrows(java.io.IOException.class,()->BoundedHttp.send(HttpClient.newHttpClient(),HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/slow")).build(),64,Duration.ofMillis(250)));
            assertTrue(headers.await(1,TimeUnit.SECONDS));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)<1_000);
        }finally{server.stop(0);}
    }
}
