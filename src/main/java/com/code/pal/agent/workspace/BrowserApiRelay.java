package com.code.pal.agent.workspace;

import com.code.pal.agent.runtime.RunCancellation;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/** Private file transport between an offline browser and one backend-selected candidate API.
 * Request/response bodies never enter model diagnostics or logs. Only the relay directory is writable. */
final class BrowserApiRelay implements AutoCloseable {
    enum Scope { API, SUPABASE }
    static final int MAX_BYTES = 1_048_576;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> REQUEST_HEADERS = Set.of("content-type", "accept", "authorization");
    private static final Set<String> SUPABASE_HEADERS = Set.of("content-type", "accept", "authorization", "apikey", "prefer", "range", "range-unit",
            "x-client-info", "x-supabase-api-version", "accept-profile", "content-profile");
    private static final Pattern SUPABASE_PATH = Pattern.compile("/(?:rest/v1(?:/[A-Za-z0-9._~!$&'()*+,;=:@%-]*)*|auth/v1/(?:signup|token|logout|user|settings|health))/?");
    private static final Pattern API_PATH = Pattern.compile("/api(?:/[A-Za-z0-9._~!$&'()*+,;=:@%-]*)*");
    private static final Pattern AMBIGUOUS_PATH_ENCODING = Pattern.compile("%(?:2f|5c|40|25)",
            Pattern.CASE_INSENSITIVE);
    private final Path directory;
    private final SandboxPort.ServerEndpoint endpoint;
    private final URI candidateBase;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final Scope scope;
    private final ExecutorService worker = Executors
            .newSingleThreadExecutor(Thread.ofVirtual().name("candidate-api-relay").factory());
    private final RunCancellation cancellation = RunCancellation.current();
    private volatile boolean closing;
    private volatile CompletableFuture<?> active;
    private volatile Throwable failure;

    record Request(String method, String path, Map<String, String> headers, String body) {
    }

    record Reply(int status, Map<String, String> headers, String body, boolean unavailable) {
    }

    BrowserApiRelay(Path directory, SandboxPort.ServerEndpoint endpoint) throws IOException {
        this(directory, endpoint, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    BrowserApiRelay(Path directory, SandboxPort.ServerEndpoint endpoint, HttpClient http) throws IOException {
        this(directory, endpoint, http, Scope.API);
    }

    BrowserApiRelay(Path directory, SandboxPort.ServerEndpoint endpoint, Scope scope) throws IOException {
        this(directory, endpoint, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), scope);
    }

    private BrowserApiRelay(Path directory, SandboxPort.ServerEndpoint endpoint, HttpClient http, Scope scope) throws IOException {
        this.directory = directory;
        this.scope = scope;
        this.endpoint = Objects.requireNonNull(endpoint);
        this.http = Objects.requireNonNull(http);
        candidateBase = URI.create(endpoint.baseUrl());
        if (!"http".equals(candidateBase.getScheme()) || candidateBase.getHost() == null
                || candidateBase.getUserInfo() != null || candidateBase.getQuery() != null
                || candidateBase.getFragment() != null
                || !(candidateBase.getPath().isEmpty() || candidateBase.getPath().equals("/"))) {
            throw new IllegalArgumentException("Invalid candidate endpoint");
        }
        Files.createDirectory(directory);
        worker.submit(this::serve);
    }

    private void serve() {
        int count = 0;
        try {
            while (!closing && !cancellation.isCancelled()) {
                try (var requests = Files.newDirectoryStream(directory, "*.request.json")) {
                    for (Path file : requests) {
                        if (closing)
                            break;
                        String name = file.getFileName().toString();
                        if (!name.matches("[a-f0-9]{32}\\.request\\.json"))
                            throw new IOException("Invalid relay request identity");
                        Reply reply;
                        if (++count > 128)
                            reply = unavailable();
                        else
                            try {
                                reply = forward(mapper.readValue(read(file, MAX_BYTES * 2), Request.class));
                            } catch (Exception rejected) {
                                reply = unavailable();
                            }
                        Path response = directory.resolve(name.replace(".request.json", ".response.json"));
                        Path temporary = directory.resolve(name.replace(".request.json", ".tmp"));
                        Files.writeString(temporary, mapper.writeValueAsString(reply), StandardOpenOption.CREATE_NEW);
                        Files.move(temporary, response, StandardCopyOption.ATOMIC_MOVE);
                        Files.delete(file);
                    }
                }
                Thread.sleep(15);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch (Throwable unavailable) {
            failure = unavailable;
        }
    }

    private Reply forward(Request data) throws Exception {
        if (data == null || !METHODS.contains(data.method()) || data.path() == null
                || data.path().length() > 2048) {
            throw new IOException("Invalid API request");
        }
        URI target = resolveTarget(data.path());
        byte[] body = Base64.getDecoder().decode(data.body() == null ? "" : data.body());
        if (body.length > MAX_BYTES || data.headers() == null || data.headers().size() > 16) {
            throw new IOException("API request exceeds capacity");
        }
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5));
        for (var header : data.headers().entrySet()) {
            if ((scope == Scope.SUPABASE ? SUPABASE_HEADERS : REQUEST_HEADERS).contains(header.getKey().toLowerCase(Locale.ROOT))) {
                if (header.getValue() == null || header.getValue().length() > 8192) {
                    throw new IOException("Invalid API header");
                }
                request.header(header.getKey(), header.getValue());
            }
        }
        endpoint.headers().forEach(request::header);
        request.method(data.method(), body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        var future = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        active = future;
        try (var stop = cancellation.onCancel(() -> future.cancel(true))) {
            var response = future.get(5, TimeUnit.SECONDS);
            if (response.headers().firstValue("x-codepal-relay-error").isPresent()) {
                response.body().close();
                return unavailable();
            }
            try (var stream = response.body();
                    var stopRead = cancellation.onCancel(() -> {
                        try {
                            stream.close();
                        } catch (IOException ignored) {
                            // Cancellation is best-effort; the normal close path still owns cleanup.
                        }
                    });
                    var reader = Executors.newVirtualThreadPerTaskExecutor()) {
                var reading = reader.submit(() -> stream.readNBytes(MAX_BYTES + 1));
                byte[] bytes;
                try {
                    bytes = reading.get(5, TimeUnit.SECONDS);
                } finally {
                    stream.close();
                    reading.cancel(true);
                }
                if (bytes.length > MAX_BYTES) {
                    throw new IOException("API response exceeds capacity");
                }
                var headers = new LinkedHashMap<String, String>();
                headers.put("content-type", response.headers().firstValue("content-type").orElse("application/octet-stream"));
                if (scope == Scope.SUPABASE) {
                    for (String name : Set.of("content-range", "range-unit", "preference-applied")) {
                        response.headers().firstValue(name).filter(value -> value.length() <= 8192).ifPresent(value -> headers.put(name, value));
                    }
                }
                return new Reply(response.statusCode(), Map.copyOf(headers), Base64.getEncoder().encodeToString(bytes), false);
            }
        } finally {
            active = null;
            future.cancel(true);
        }
    }

    private URI resolveTarget(String value) throws IOException {
        URI path = URI.create(value);
        String rawPath = path.getRawPath();
        if (path.isAbsolute() || path.getRawAuthority() != null || path.getFragment() != null
                || rawPath == null || !(scope == Scope.SUPABASE ? SUPABASE_PATH : API_PATH).matcher(rawPath).matches()
                || AMBIGUOUS_PATH_ENCODING.matcher(rawPath).find()) {
            throw new IOException("Invalid API path");
        }

        String decoded = path.getPath();
        if (decoded.contains("//") || decoded.contains("\\")
                || decoded.codePoints().anyMatch(Character::isISOControl)) {
            throw new IOException("Invalid API path");
        }
        for (String segment : decoded.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IOException("Invalid API path");
            }
        }

        // Resolve only a validated absolute path, then prove the transport cannot change origin.
        URI target = candidateBase.resolve(path);
        if (!candidateBase.getScheme().equals(target.getScheme())
                || !candidateBase.getHost().equalsIgnoreCase(target.getHost())
                || candidateBase.getPort() != target.getPort() || target.getRawUserInfo() != null) {
            throw new IOException("Invalid candidate API origin");
        }
        return target;
    }

    private static Reply unavailable() {
        return new Reply(503, Map.of("content-type", "application/json"), Base64.getEncoder().encodeToString(
                "{\"error\":\"Candidate API validation unavailable\"}".getBytes(StandardCharsets.UTF_8)), true);
    }

    private static String read(Path file, int limit) throws IOException {
        try (var in = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit)
                throw new IOException("Relay data exceeds capacity");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() {
        closing = true;
        var sending = active;
        if (sending != null)
            sending.cancel(true);
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(12, TimeUnit.SECONDS))
                throw new IllegalStateException("Candidate relay cleanup pending");
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files)
                    Files.delete(file);
            }
            Files.delete(directory);
            if (failure != null)
                throw new IllegalStateException("Candidate relay unavailable", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Candidate relay cleanup interrupted");
        } catch (IOException failed) {
            throw new UncheckedIOException("Candidate relay cleanup pending", failed);
        }
    }
}
