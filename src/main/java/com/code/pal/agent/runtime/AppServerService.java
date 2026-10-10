package com.code.pal.agent.runtime;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.stack.MernPack;
import com.code.pal.agent.stack.StackPacks;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.database.GeneratedDatabaseService;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs a MERN project's API server for its preview and published site.
 *
 * <p>After every commit the server package is built on a copy of the project (TypeScript compiled with the
 * package's own tsconfig) and the result, with its dependencies linked in, becomes
 * {@code .codepal/server/<id>} named by a {@code CURRENT} file, like the served site. The server runs in
 * the sandbox with the project read-only, no internet and its own database user ({@code MONGODB_URI});
 * it is started on the first request when not running and stopped after a while without requests.
 * Requests under {@code /api} on a site's host are forwarded to it.
 */
@Slf4j
@Service
public class AppServerService {
    static final String SERVERS = ProjectPaths.INTERNAL + "/server";
    static final String CURRENT = "CURRENT";
    private static final String WORK = ProjectPaths.INTERNAL + "/server-build";
    public static final String APP = "app";
    static final int PORT = 3000;
    private static final int MAX_BODY_BYTES = 10_000_000;
    private static final Duration START_TIMEOUT = Duration.ofSeconds(25);
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "upgrade", "te", "trailer", "host", "content-length", "expect");

    /** What the server is doing; {@code detail} says why it is not running (a build error, a crash log). */
    public record Status(State state, String detail) {
        public enum State { NONE, STOPPED, STARTING, RUNNING, FAILED }
    }

    private final StackPacks stacks;
    private final ProjectFiles files;
    private final SandboxPort sandbox;
    private final DependencyService dependencies;
    private final GeneratedDatabaseService databases;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastUsed = new ConcurrentHashMap<>();
    private final Map<UUID, Status> statuses = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired
    public AppServerService(StackPacks stacks, ProjectFiles files, SandboxPort sandbox, DependencyService dependencies,
                            GeneratedDatabaseService databases, AgentProperties properties, ObjectMapper objectMapper) {
        this(stacks, files, sandbox, dependencies, databases, properties, objectMapper, Clock.systemUTC());
    }

    AppServerService(StackPacks stacks, ProjectFiles files, SandboxPort sandbox, DependencyService dependencies,
                     GeneratedDatabaseService databases, AgentProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.stacks = stacks;
        this.files = files;
        this.sandbox = sandbox;
        this.dependencies = dependencies;
        this.databases = databases;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Whether the project has an API server to run (a MERN project with a built server). */
    public boolean hasServer(UUID projectId) {
        return currentBuild(projectId) != null;
    }

    public Status status(UUID projectId) {
        Status known = statuses.get(projectId);
        if (known != null) return known;
        return new Status(hasServer(projectId) ? Status.State.STOPPED : Status.State.NONE, null);
    }

    /** {@link #deploy} without waiting: after a commit, a rollback or new environment variables. */
    public void deployLater(UUID projectId) {
        background.submit(() -> {
            try {
                deploy(projectId);
            } catch (RuntimeException error) {
                log.warn("app_server_deploy_crashed projectId={} reason={}", projectId, error.getMessage());
            }
        });
    }

    /**
     * Builds the server from the project's current files and restarts it on the new build when it was
     * running. A project that is not (or no longer) MERN has its server stopped and builds removed.
     */
    public void deploy(UUID projectId) {
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            if (!(stacks.detect(files, projectId, ".") instanceof MernPack)) {
                retire(projectId);
                return;
            }
            String previous = currentBuild(projectId);
            String id = UUID.randomUUID().toString();
            String error = build(projectId, SERVERS + "/" + id);
            if (error != null) {
                statuses.put(projectId, new Status(Status.State.FAILED, error));
                log.warn("app_server_build_failed projectId={} reason={}", projectId, error);
                return;
            }
            files.writeText(projectId, SERVERS, CURRENT, id);
            Set<String> keep = new HashSet<>(List.of(id));
            if (previous != null) keep.add(previous.substring(SERVERS.length() + 1));
            prune(projectId, keep);
            log.info("app_server_built projectId={} build={}", projectId, id);
            boolean wasRunning = sandbox.server(projectId, APP) != null;
            statuses.put(projectId, new Status(Status.State.STOPPED, null));
            if (wasRunning) startLocked(projectId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Forwards a site request under {@code /api} to the project's server, starting it when needed. The
     * response is the server's, or 502/503 with a JSON error when it cannot be reached.
     */
    public void proxy(UUID projectId, HttpServletRequest request, HttpServletResponse response) throws IOException {
        lastUsed.put(projectId, clock.instant());
        SandboxPort.ServerEndpoint endpoint;
        try {
            endpoint = ensureRunning(projectId);
        } catch (RuntimeException error) {
            sendError(response, 503, "The app server is not running: " + error.getMessage());
            return;
        }
        String target = endpoint.baseUrl() + request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            sendError(response, 413, "Request body too large");
            return;
        }
        HttpRequest.Builder forward = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30))
                .method(request.getMethod(), body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : java.util.Collections.list(request.getHeaderNames())) {
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT)) || name.toLowerCase(Locale.ROOT).startsWith("x-codepal-")) continue;
            for (String value : java.util.Collections.list(request.getHeaders(name))) {
                try {
                    forward.header(name, value);
                } catch (IllegalArgumentException restricted) {
                    // a header the client may not set (handled by the HTTP client itself)
                }
            }
        }
        endpoint.headers().forEach(forward::header);
        HttpResponse<byte[]> reply;
        try {
            reply = http.send(forward.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            sendError(response, 502, "Interrupted");
            return;
        } catch (IOException unreachable) {
            sendError(response, 502, "The app server did not answer");
            return;
        }
        response.setStatus(reply.statusCode());
        reply.headers().map().forEach((name, values) -> {
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT)) || name.startsWith(":")) return;
            values.forEach(value -> response.addHeader(name, value));
        });
        response.setContentLength(reply.body().length);
        response.getOutputStream().write(reply.body());
    }

    /** The project's server for a request: running already, or started now on the current build. */
    private SandboxPort.ServerEndpoint ensureRunning(UUID projectId) {
        SandboxPort.ServerEndpoint running = sandbox.server(projectId, APP);
        if (running != null) return running;
        ReentrantLock lock = lockFor(projectId);
        lock.lock();
        try {
            running = sandbox.server(projectId, APP);
            return running != null ? running : startLocked(projectId);
        } finally {
            lock.unlock();
        }
    }

    private SandboxPort.ServerEndpoint startLocked(UUID projectId) {
        String build = currentBuild(projectId);
        if (build == null) throw new IllegalStateException("the server has not been built");
        statuses.put(projectId, new Status(Status.State.STARTING, null));
        String uri = databases.connectionUri(projectId, sandbox.databaseAddress(), false);
        SandboxPort.ServerEndpoint endpoint;
        try {
            endpoint = sandbox.startServer(projectId, APP, build, List.of("node", entry(projectId, build)),
                    Map.of("MONGODB_URI", uri, "NODE_ENV", "production"), PORT);
            awaitListening(projectId, APP, endpoint);
        } catch (RuntimeException failure) {
            String logs = sandbox.serverLogs(projectId, APP, 40);
            sandbox.stopServer(projectId, APP);
            statuses.put(projectId, new Status(Status.State.FAILED, failure.getMessage() + (logs.isBlank() ? "" : "\n" + logs)));
            throw new IllegalStateException(failure.getMessage(), failure);
        }
        lastUsed.put(projectId, clock.instant());
        statuses.put(projectId, new Status(Status.State.RUNNING, null));
        return endpoint;
    }

    /**
     * Waits until the server answers HTTP at all (any status: an app without /api/health is up too), or
     * fails with its last output when it exits or does not listen in time.
     */
    public void awaitListening(UUID projectId, String name, SandboxPort.ServerEndpoint endpoint) {
        long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest.Builder probe = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/api/health"))
                        .timeout(Duration.ofSeconds(2)).GET();
                endpoint.headers().forEach(probe::header);
                int status = http.send(probe.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
                // 502 is the gateway saying the server is not listening yet.
                if (status != 502) return;
            } catch (IOException notYet) {
                // not listening yet
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
            if (sandbox.server(projectId, name) == null) {
                throw new IllegalStateException("The server exited: " + tail(sandbox.serverLogs(projectId, name, 40)));
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        }
        throw new IllegalStateException("The server did not start listening on PORT within " + START_TIMEOUT.toSeconds()
                + " s: " + tail(sandbox.serverLogs(projectId, name, 40)));
    }

    /**
     * Builds the server package of {@code source} (the project, or a validation copy) into {@code target}:
     * compiled when it is TypeScript, its dependencies linked. Returns why it failed, or null.
     */
    public String build(UUID projectId, String target) {
        return build(projectId, ".", target);
    }

    public String build(UUID projectId, String source, String target) {
        String work = WORK + "/" + UUID.randomUUID();
        try {
            sandbox.copyTree(projectId, source, work);
            DependencyService.Preparation deps = dependencies.prepare(projectId, work);
            if (deps.failed()) return deps.error();
            String server = work + "/" + MernPack.SERVER;
            if (files.regularFile(projectId, server, "tsconfig.json")) {
                SandboxPort.CommandResult compiled = sandbox.exec(projectId, work,
                        List.of("node", MernPack.SERVER + "/node_modules/typescript/bin/tsc", "-p", MernPack.SERVER, "--pretty", "false"));
                if (!compiled.success()) return "The server does not compile: " + tail(compiled.stdout() + "\n" + compiled.error());
            }
            sandbox.copyTree(projectId, server, target);
            DependencyService.Preparation linked = dependencies.prepare(projectId, target);
            if (linked.failed()) return linked.error();
            String entry = entry(projectId, target);
            if (!files.regularFile(projectId, target, entry)) return "The server's entry point " + entry + " does not exist after the build";
            return null;
        } catch (RuntimeException error) {
            return "The server build failed: " + error.getMessage();
        } finally {
            try { sandbox.removeTree(projectId, work); } catch (RuntimeException ignored) { }
        }
    }

    /** The file to run: package.json "main", else dist/index.js for TypeScript, else index.js. */
    public String entry(UUID projectId, String serverRoot) {
        try {
            Object main = objectMapper.readValue(files.readText(projectId, serverRoot, "package.json"), Map.class).get("main");
            if (main instanceof String path && !path.isBlank()) return ProjectPaths.normalize(path, false);
        } catch (RuntimeException unreadable) {
            // fall back to the conventions
        }
        return files.regularFile(projectId, serverRoot, "tsconfig.json") ? "dist/index.js" : "index.js";
    }

    /** The folder of the server build to run, or null. */
    private String currentBuild(UUID projectId) {
        try {
            if (!files.regularFile(projectId, SERVERS, CURRENT)) return null;
            String build = SERVERS + "/" + UUID.fromString(files.readText(projectId, SERVERS, CURRENT).trim());
            return files.regularFile(projectId, build, "package.json") ? build : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Servers nobody has called for a while are stopped; the next request starts them again. */
    @Scheduled(fixedDelayString = "${app.agent.sandbox.server-idle-sweep-ms:60000}")
    public void stopIdleServers() {
        Instant now = clock.instant();
        long idle = Math.max(60_000, properties.getSandbox().getServerIdleStopMs());
        for (Map.Entry<UUID, Instant> entry : lastUsed.entrySet()) {
            if (Duration.between(entry.getValue(), now).toMillis() < idle) continue;
            ReentrantLock lock = lockFor(entry.getKey());
            if (!lock.tryLock()) continue;
            try {
                sandbox.stopServer(entry.getKey(), APP);
                lastUsed.remove(entry.getKey(), entry.getValue());
                statuses.put(entry.getKey(), new Status(Status.State.STOPPED, null));
                log.info("app_server_stopped_idle projectId={}", entry.getKey());
            } catch (RuntimeException error) {
                log.warn("app_server_stop_failed projectId={} reason={}", entry.getKey(), error.getMessage());
            } finally {
                lock.unlock();
            }
        }
    }

    private void retire(UUID projectId) {
        if (!files.exists(projectId, SERVERS, CURRENT)) return;
        sandbox.stopServer(projectId, APP);
        files.delete(projectId, SERVERS, CURRENT);
        prune(projectId, Set.of());
        statuses.remove(projectId);
    }

    private void prune(UUID projectId, Set<String> keep) {
        try {
            for (ProjectFiles.Entry entry : files.list(projectId, SERVERS, 1)) {
                if (entry.directory() && !keep.contains(entry.path())) sandbox.removeTree(projectId, SERVERS + "/" + entry.path());
            }
        } catch (RuntimeException error) {
            log.warn("app_server_prune_failed projectId={} reason={}", projectId, error.getMessage());
        }
    }

    private ReentrantLock lockFor(UUID projectId) {
        return locks.computeIfAbsent(projectId, ignored -> new ReentrantLock());
    }

    private static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream().write(("{\"error\":" + quote(message) + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String tail(String output) {
        String trimmed = output.strip();
        return trimmed.length() <= 1_500 ? trimmed : "..." + trimmed.substring(trimmed.length() - 1_500);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }
}
