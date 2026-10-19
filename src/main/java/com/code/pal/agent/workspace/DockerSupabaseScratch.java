package com.code.pal.agent.workspace;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.llm.LlmCancelledException;
import com.code.pal.agent.workspace.SandboxPort.CommandResult;
import com.code.pal.agent.workspace.SandboxPort.ServerEndpoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Fresh, network-isolated PostgreSQL/GoTrue/PostgREST. No customer connection is accepted or consulted. */
@Component
@ConditionalOnProperty(prefix = "app.agent.sandbox", name = "required", havingValue = "true")
public final class DockerSupabaseScratch implements SupabaseScratch {
    public static final String BROWSER_ORIGIN = "http://codepal-supabase.invalid";
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final DockerProjectEnvironment.CommandRunner docker;
    private final JsonNode images;
    private final SupabaseScratchResources resources;
    private ProjectLeaseService leases;

    @Autowired
    public void useLeases(ProjectLeaseService leases) {
        this.leases = leases;
    }

    @Autowired
    public DockerSupabaseScratch(AgentProperties properties, ObjectMapper mapper) {
        this(properties, mapper, new DockerCommands(properties));
    }

    DockerSupabaseScratch(AgentProperties properties, ObjectMapper mapper, DockerProjectEnvironment.CommandRunner docker) {
        this.properties = properties;
        this.mapper = mapper;
        this.docker = docker;
        images = mapper.readTree(resource("runtime.json"));
        resources = new SupabaseScratchResources(properties, mapper, docker);
    }

    SupabaseScratchResources resources() { return resources; }

    @Override
    public Session open(UUID project, UUID run, List<Migration> migrations) {
        var settings = properties.getSupabaseValidation();
        if (!settings.isEnabled()) {
            throw new SupabaseScratchException("Isolated Supabase acceptance is disabled", false);
        }
        if (project == null || run == null || migrations == null
                || migrations.size() > settings.getMaxMigrations()
                || migrations.stream().anyMatch(migration -> migration == null || migration.sql() == null || migration.sql().isBlank())
                || migrations.stream().mapToLong(migration -> migration.sql().getBytes(StandardCharsets.UTF_8).length).sum()
                        > settings.getMaxMigrationBytes()) {
            throw new SupabaseScratchException("A bounded complete schema migration history is required for isolated acceptance", true);
        }
        var session = new ScratchSession(project, run);
        try {
            session.start(migrations);
            return session;
        } catch (RuntimeException failed) {
            try { session.close(); }
            catch (RuntimeException cleanup) { cleanup.addSuppressed(failed); throw cleanup; }
            throw failed;
        }
    }

    private final class ScratchSession implements Session {
        private final String id = "codepal-supa-check-" + UUID.randomUUID();
        private final UUID project;
        private final UUID run;
        private final String password = random();
        private final String jwtSecret = random();
        private final String publicKey = token(jwtSecret);
        private boolean networkPlanned;
        private boolean closed;
        private ServerEndpoint endpoint;
        private String databaseUrl;

        ScratchSession(UUID project, UUID run) {
            this.project = project;
            this.run = run;
        }

        private void start(List<Migration> migrations) {
            requireRunning();
            networkPlanned = true;
            var network = new ArrayList<>(List.of("network", "create", "--internal"));
            network.addAll(resources.labels(identity()));
            network.add(id);
            mutate(network).requireSuccess();
            create("db", images.path("postgres").asString(), Map.of("POSTGRES_PASSWORD", password, "PGDATA", "/data"),
                    List.of("--user", "999:999", "--tmpfs", "/data:rw,uid=999,gid=999,mode=0700",
                            "--tmpfs", "/var/run/postgresql:rw,uid=999,gid=999,mode=0775"),
                    List.of("postgres", "-c", "shared_buffers=64MB", "-c", "max_connections=40"));
            awaitDatabase();
            create("proxy", properties.getSandbox().getImage(),
                    Map.of("AUTH_HOST", id + "-auth", "REST_HOST", id + "-rest", "DB_HOST", id + "-db"),
                    List.of("--user", "1000:1000", "-p", "127.0.0.1::8000", "-p", "127.0.0.1::5433"),
                    List.of("node", "--input-type=module", "--eval", resource("proxy.mjs")));
            databaseUrl = "jdbc:postgresql://" + properties.getSandbox().getGatewayHost() + ":"
                    + port("proxy", "5433/tcp") + "/postgres?connectTimeout=2&socketTimeout=30";
            awaitSql();
            sql(resource("bootstrap.sql").replace("{{password}}", password), false);
            create("auth", images.path("auth").asString(), Map.ofEntries(
                    Map.entry("GOTRUE_API_HOST", "0.0.0.0"), Map.entry("GOTRUE_API_PORT", "9999"),
                    Map.entry("API_EXTERNAL_URL", BROWSER_ORIGIN + "/auth/v1"),
                    Map.entry("GOTRUE_SITE_URL", BROWSER_ORIGIN), Map.entry("GOTRUE_DB_DRIVER", "postgres"),
                    Map.entry("GOTRUE_DB_DATABASE_URL", "postgres://supabase_auth_admin:" + password + "@" + id + "-db:5432/postgres?search_path=auth"),
                    Map.entry("GOTRUE_JWT_SECRET", jwtSecret), Map.entry("GOTRUE_JWT_AUD", "authenticated"),
                    Map.entry("GOTRUE_JWT_ADMIN_ROLES", "service_role"), Map.entry("GOTRUE_JWT_DEFAULT_GROUP_NAME", "authenticated"),
                    Map.entry("GOTRUE_EXTERNAL_EMAIL_ENABLED", "true"), Map.entry("GOTRUE_MAILER_AUTOCONFIRM", "true"),
                    Map.entry("GOTRUE_EXTERNAL_ANONYMOUS_USERS_ENABLED", "false"), Map.entry("GOTRUE_DISABLE_SIGNUP", "false"),
                    Map.entry("GOTRUE_RATE_LIMIT_EMAIL_SENT", "100")), List.of(), List.of());
            endpoint = new ServerEndpoint("http://" + properties.getSandbox().getGatewayHost() + ":"
                    + port("proxy", "8000/tcp"), Map.of());
            awaitHttp("/auth/v1/health");
            sql("GRANT REFERENCES, TRIGGER ON auth.users TO validation_owner;", false);
            for (Migration migration : migrations) {
                requireRunning();
                sql(migration.sql(), true);
            }
            // Start REST only after migrations commit: a pre-existing HTTP 200 would not prove its schema cache refreshed.
            create("rest", images.path("rest").asString(), Map.of(
                    "PGRST_DB_URI", "postgres://authenticator:" + password + "@" + id + "-db:5432/postgres",
                    "PGRST_DB_SCHEMAS", "public", "PGRST_DB_ANON_ROLE", "anon", "PGRST_JWT_SECRET", jwtSecret,
                    "PGRST_DB_MAX_ROWS", "1000"), List.of(), List.of());
            awaitHttp("/rest/v1/");
        }

        private void create(String service, String image, Map<String, String> environment, List<String> options, List<String> args) {
            requireRunning();
            String name = id + "-" + service;
            var create = new ArrayList<>(List.of("create", "--name", name));
            create.addAll(resources.labels(identity()));
            create.addAll(List.of("--network", id, "--pull", "never", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                    "--cpus", Double.toString(Math.max(0.5, properties.getSandbox().getCpus())), "--memory", Math.max(128, service.equals("db")
                            ? properties.getSupabaseValidation().getDatabaseMemoryMb()
                            : properties.getSupabaseValidation().getServiceMemoryMb()) + "m", "--pids-limit",
                    Integer.toString(Math.max(32, properties.getSupabaseValidation().getMaxProcesses()))));
            create.addAll(options);
            environment.forEach((key, value) -> create.addAll(List.of("-e", key + "=" + value)));
            create.add(image);
            create.addAll(args);
            // Complete creation before observing Stop; no generated process has started yet.
            if (!mutate(create).success()) throw new SupabaseScratchException("Cannot create isolated " + service + " container", false);
            requireRunning();
            if (service.equals("proxy") && !mutate(List.of("network", "connect", "bridge", name)).success()) {
                throw new SupabaseScratchException("Cannot attach the isolated validation proxy", false);
            }
            if (!mutate(List.of("start", name)).success()) throw new SupabaseScratchException("Cannot start isolated " + service + " container", false);
        }

        private SupabaseScratchResources.Identity identity() { return new SupabaseScratchResources.Identity(id, project, run); }

        private CommandResult mutate(List<String> args) {
            requireRunning();
            if (leases == null) return completeMutation(args);
            var fence = RunFence.current();
            if (fence == null || !fence.runId().equals(run) || !fence.projectId().equals(project)) throw new LeaseLostException();
            return leases.guarded(project, () -> completeMutation(args));
        }

        private CommandResult completeMutation(List<String> args) {
            // Recovery cannot race late CLI creation/start. Complete bounded mutations under ownership, then observe Stop.
            try (var ignored = RunCancellation.bind(RunCancellation.NONE)) { return command(args); }
        }

        private void sql(String sql, boolean generated) {
            // JDBC sends SQL only. psql stdin would allow client-side shell meta-commands before database permissions apply.
            try (var connection = DriverManager.getConnection(databaseUrl, generated ? "validation_owner" : "postgres", password)) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement();
                        var stop = RunCancellation.current().onCancel(() -> Thread.ofVirtual().start(() -> {
                            try { connection.close(); } catch (SQLException ignored) { }
                        }))) {
                    statement.setQueryTimeout(Math.max(1, (int) (properties.getSupabaseValidation().getStartupTimeoutMs() / 1000)));
                    statement.execute(sql);
                    requireRunning();
                    connection.commit();
                }
            } catch (SQLException failed) {
                requireRunning();
                String state = failed.getSQLState();
                String suffix = state != null && state.matches("[A-Z0-9]{5}") ? " (SQLSTATE " + state + ")" : "";
                throw new SupabaseScratchException(generated ? "Candidate schema migration failed in the isolated database" + suffix
                        : "Isolated Supabase bootstrap is unavailable", generated);
            }
        }

        private String port(String service, String port) {
            var result = command(List.of("inspect", "--format", "{{json .NetworkSettings.Ports}}", id + "-" + service));
            if (result.success()) {
                var bindings = mapper.readTree(result.stdout()).path(port);
                if (bindings.isArray() && bindings.size() == 1) {
                    var binding = bindings.get(0);
                    String value = binding.path("HostPort").asString();
                    if (binding.path("HostIp").asString().equals("127.0.0.1") && value.matches("[0-9]{1,5}")
                            && Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= 65535) return value;
                }
            }
            throw new SupabaseScratchException("An isolated service has no proved loopback-only port binding", false);
        }

        private void awaitDatabase() {
            long until = System.nanoTime() + Duration.ofMillis(properties.getSupabaseValidation().getStartupTimeoutMs()).toNanos();
            do {
                requireRunning();
                if (command(List.of("exec", id + "-db", "pg_isready", "-U", "postgres")).success()) return;
                pause();
            } while (System.nanoTime() < until);
            throw unavailable();
        }

        private void awaitSql() {
            long until = System.nanoTime() + Duration.ofMillis(properties.getSupabaseValidation().getStartupTimeoutMs()).toNanos();
            do {
                requireRunning();
                try (var connection = DriverManager.getConnection(databaseUrl, "postgres", password)) {
                    if (connection.isValid(1)) return;
                } catch (SQLException waiting) {
                    // The trusted proxy can still be starting after Docker reports the process running.
                }
                pause();
            } while (System.nanoTime() < until);
            throw unavailable();
        }

        private void awaitHttp(String path) {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(HttpClient.Redirect.NEVER).build();
            long until = System.nanoTime() + Duration.ofMillis(properties.getSupabaseValidation().getStartupTimeoutMs()).toNanos();
            do {
                requireRunning();
                try {
                    var response = http.send(HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                            .timeout(Duration.ofSeconds(1)).build(), HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() == 200) return;
                } catch (IOException waiting) {
                    // Fresh auth/REST processes can take a moment to initialize.
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    throw new LlmCancelledException();
                }
                pause();
            } while (System.nanoTime() < until);
            throw unavailable();
        }

        @Override public ServerEndpoint endpoint() { return endpoint; }
        @Override public String publicKey() { return publicKey; }
        @Override public Map<String, String> buildVariables() {
            return Map.of("VITE_SUPABASE_URL", BROWSER_ORIGIN, "VITE_SUPABASE_ANON_KEY", publicKey);
        }
        @Override public void restartRest() {
            if (!mutate(List.of("restart", id + "-rest")).success()) throw unavailable();
            awaitHttp("/rest/v1/");
        }

        @Override
        public void close() {
            if (closed) return;
            if (networkPlanned && !resources.remove(identity())) {
                throw new SupabaseScratchException("Isolated Supabase process cleanup needs verification; resources retained", false);
            }
            closed = true;
        }
    }

    private CommandResult command(List<String> args) {
        return docker.run(args, Duration.ofMillis(Math.max(1_000, properties.getSupabaseValidation().getCommandTimeoutMs())), null, 8192);
    }

    private String token(String secret) {
        try {
            var encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("role", "anon", "iss", "supabase",
                    "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plusSeconds(3600).getEpochSecond())));
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return header + "." + payload + "." + encoder.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failed) {
            throw unavailable();
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String resource(String name) {
        try (var stream = DockerSupabaseScratch.class.getResourceAsStream("/sandbox/supabase/" + name)) {
            if (stream == null) throw unavailable();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) { throw unavailable(); }
    }

    private static void pause() {
        try { Thread.sleep(100); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new LlmCancelledException(); }
    }

    private static void requireRunning() {
        if (RunCancellation.current().isCancelled()) throw new LlmCancelledException();
    }

    private static SupabaseScratchException unavailable() {
        return new SupabaseScratchException("Isolated Supabase validation infrastructure is unavailable", false);
    }
}
