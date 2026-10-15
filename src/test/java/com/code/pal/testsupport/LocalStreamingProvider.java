package com.code.pal.testsupport;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/** Finite loopback HTTP fixture. Never loads real endpoints or credentials. */
public final class LocalStreamingProvider implements AutoCloseable {
    private record Reply(int status, String usage) {}
    private final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch released=new java.util.concurrent.CountDownLatch(1);
    private final HttpServer server;
    public LocalStreamingProvider() throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
            Reply reply=replies.poll();
            entered.countDown();
            if(reply!=null && reply.status()==102) {
                try { released.await(5,java.util.concurrent.TimeUnit.SECONDS); }
                catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
                exchange.sendResponseHeaders(503,-1);exchange.close();return;
            }
            if(reply==null) { exchange.sendResponseHeaders(418,-1); exchange.close(); return; }
            if(reply.status()!=200) {
                exchange.getResponseHeaders().add("Retry-After","0");
                exchange.sendResponseHeaders(reply.status(),-1); exchange.close(); return;
            }
            String body="data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n"
                    +(reply.usage()==null ? "" : "data: {\"usage\":"+reply.usage()+"}\n\n")+"data: [DONE]\n\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }
    public LocalStreamingProvider error(int status) { replies.add(new Reply(status,null)); return this; }
    public LocalStreamingProvider waitForCancellation() { replies.add(new Reply(102,null));return this; }
    public boolean awaitRequest() throws InterruptedException { return entered.await(5,java.util.concurrent.TimeUnit.SECONDS); }
    public LocalStreamingProvider answer(Integer prompt,Integer completion) {
        replies.add(new Reply(200,prompt==null ? null : "{\"prompt_tokens\":"+prompt+",\"completion_tokens\":"+completion+"}")); return this;
    }
    public String baseUrl() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    public int requests() { return requests.get(); }
    public void assertConsumed() { if(!replies.isEmpty()) throw new AssertionError("Unused HTTP fixture replies: "+replies.size()); }
    @Override public void close() { released.countDown();server.stop(0); }
}
