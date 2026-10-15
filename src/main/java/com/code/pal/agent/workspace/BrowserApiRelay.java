package com.code.pal.agent.workspace;

import com.code.pal.agent.runtime.RunCancellation;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Private file transport between an offline browser and one backend-selected candidate API.
 * Request/response bodies never enter model diagnostics or logs. Only the relay directory is writable. */
final class BrowserApiRelay implements AutoCloseable {
    static final int MAX_BYTES=1_048_576;
    private static final Set<String> METHODS=Set.of("GET","HEAD","POST","PUT","PATCH","DELETE","OPTIONS");
    private static final Set<String> REQUEST_HEADERS=Set.of("content-type","accept","authorization");
    private final Path directory;
    private final SandboxPort.ServerEndpoint endpoint;
    private final ObjectMapper mapper=new ObjectMapper();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("candidate-api-relay").factory());
    private final RunCancellation cancellation=RunCancellation.current();
    private volatile boolean closing;
    private volatile CompletableFuture<?> active;
    private volatile Throwable failure;
    record Request(String method,String path,Map<String,String> headers,String body) {}
    record Reply(int status,Map<String,String> headers,String body,boolean unavailable) {}

    BrowserApiRelay(Path directory,SandboxPort.ServerEndpoint endpoint) throws IOException {
        this.directory=directory;this.endpoint=Objects.requireNonNull(endpoint);
        URI base=URI.create(endpoint.baseUrl());
        if(!"http".equals(base.getScheme()) || base.getHost()==null || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))throw new IllegalArgumentException("Invalid candidate endpoint");
        Files.createDirectory(directory);
        worker.submit(this::serve);
    }
    private void serve() {
        int count=0;
        try {
            while(!closing && !cancellation.isCancelled()) {
                try(var requests=Files.newDirectoryStream(directory,"*.request.json")) {
                    for(Path file:requests) {
                        if(closing)break;
                        String name=file.getFileName().toString();
                        if(!name.matches("[a-f0-9]{32}\\.request\\.json"))throw new IOException("Invalid relay request identity");
                        Reply reply;
                        if(++count>128)reply=unavailable();
                        else try { reply=forward(mapper.readValue(read(file,MAX_BYTES*2),Request.class)); }
                        catch(Exception rejected){reply=unavailable();}
                        Path response=directory.resolve(name.replace(".request.json",".response.json"));
                        Path temporary=directory.resolve(name.replace(".request.json",".tmp"));
                        Files.writeString(temporary,mapper.writeValueAsString(reply),StandardOpenOption.CREATE_NEW);
                        Files.move(temporary,response,StandardCopyOption.ATOMIC_MOVE);
                        Files.delete(file);
                    }
                }
                Thread.sleep(15);
            }
        }catch(InterruptedException stop){Thread.currentThread().interrupt();}
        catch(Throwable unavailable){failure=unavailable;}
    }
    private Reply forward(Request data) throws Exception {
        if(data==null || !METHODS.contains(data.method()) || data.path()==null || data.path().length()>2048)throw new IOException("Invalid API request");
        URI path=URI.create(data.path());String decoded=path.getPath();
        if(path.isAbsolute() || path.getRawAuthority()!=null || path.getFragment()!=null || decoded==null
                || !(decoded.equals("/api") || decoded.startsWith("/api/")) || decoded.contains("//") || decoded.contains("\\")
                || Arrays.stream(decoded.split("/")).anyMatch(p->p.equals(".") || p.equals("..")))throw new IOException("Invalid API path");
        byte[] body=Base64.getDecoder().decode(data.body()==null?"":data.body());
        if(body.length>MAX_BYTES || data.headers()==null || data.headers().size()>16)throw new IOException("API request exceeds capacity");
        var request=HttpRequest.newBuilder(URI.create(endpoint.baseUrl().replaceAll("/$","")+data.path())).timeout(Duration.ofSeconds(5));
        for(var header:data.headers().entrySet()) {
            if(REQUEST_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                if(header.getValue()==null || header.getValue().length()>8192)throw new IOException("Invalid API header");
                request.header(header.getKey(),header.getValue());
            }
        }
        endpoint.headers().forEach(request::header);
        request.method(data.method(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        var future=http.sendAsync(request.build(),HttpResponse.BodyHandlers.ofInputStream());active=future;
        try(var stop=cancellation.onCancel(()->future.cancel(true))) {
            var response=future.get(5,TimeUnit.SECONDS);
            if(response.headers().firstValue("x-codepal-relay-error").isPresent()){response.body().close();return unavailable();}
            try(var stream=response.body();var stopRead=cancellation.onCancel(()->{try{stream.close();}catch(IOException ignored){}});
                var reader=Executors.newVirtualThreadPerTaskExecutor()) {
                var reading=reader.submit(()->stream.readNBytes(MAX_BYTES+1));byte[] bytes;
                try{bytes=reading.get(5,TimeUnit.SECONDS);}finally{stream.close();reading.cancel(true);}
                if(bytes.length>MAX_BYTES)throw new IOException("API response exceeds capacity");
                return new Reply(response.statusCode(),Map.of("content-type",response.headers().firstValue("content-type").orElse("application/octet-stream")),Base64.getEncoder().encodeToString(bytes),false);
            }
        }finally{active=null;future.cancel(true);}
    }
    private static Reply unavailable(){return new Reply(503,Map.of("content-type","application/json"),Base64.getEncoder().encodeToString("{\"error\":\"Candidate API validation unavailable\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),true);}
    private static String read(Path file,int limit) throws IOException {
        try(var in=Files.newInputStream(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IOException("Relay data exceeds capacity");
            return new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Override public void close() {
        closing=true;var sending=active;if(sending!=null)sending.cancel(true);worker.shutdownNow();
        try {
            if(!worker.awaitTermination(12,TimeUnit.SECONDS))throw new IllegalStateException("Candidate relay cleanup pending");
            try(var files=Files.newDirectoryStream(directory)){for(Path file:files)Files.delete(file);}
            Files.delete(directory);
            if(failure!=null)throw new IllegalStateException("Candidate relay unavailable",failure);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Candidate relay cleanup interrupted");}
        catch(IOException failed){throw new UncheckedIOException("Candidate relay cleanup pending",failed);}
    }
}
