package com.code.pal.project.supabase;

import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectEnvironmentVariable;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.config.DataCipher;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Against a fake Supabase (management API + project API) on loopback. */
class SupabaseServiceTest {
    private static final String REF = "abcdefghijklmnopqrst";
    private static final String ANON = "anon-key-123";
    private static final String TOKEN = "sbp_secret_token";

    private final LocalProject project = LocalProject.create();
    private final UUID projectId = project.projectId;
    private final List<String> executedSql = new CopyOnWriteArrayList<>();
    private String failingSql;
    private final HttpServer server = fakeSupabase();
    private final String base = "http://127.0.0.1:" + server.getAddress().getPort();

    private final Map<UUID, SupabaseConnection> connectionStore = new HashMap<>();
    private final List<SupabaseMigration> migrationStore = new ArrayList<>();
    private final Map<String, ProjectEnvironmentVariable> variableStore = new HashMap<>();
    private final ProjectService projects = mock(ProjectService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final DataCipher cipher = new DataCipher("test-data-key-0123456789");
    private final SupabaseService service = new SupabaseService(projects, connections(), migrations(), variables(),
            new SupabaseClient(new ObjectMapper(), base), new SelfHostedSupabase("", "", "postgres", ""), cipher, project.files, events, new FakeTransactions(),
            "^http://127\\.0\\.0\\.1:\\d+/([a-z0-9]{20})$");

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void connectingChecksTheCredentialsStoresTheTokenEncryptedAndSetsTheBuildVariables() {
        SupabaseService.Status status = service.connect(7L, projectId, base + "/" + REF + "/", ANON, TOKEN);

        assertTrue(status.connected() && status.migrationsEnabled());
        assertEquals(REF, status.projectRef());
        SupabaseConnection stored = connectionStore.get(projectId);
        assertNotEquals(TOKEN, stored.getAccessTokenCipher(), "never stored in plaintext");
        assertEquals(TOKEN, cipher.decrypt(stored.getAccessTokenCipher()));
        assertEquals(base + "/" + REF, variableStore.get("VITE_SUPABASE_URL").getValue());
        assertEquals(ANON, variableStore.get("VITE_SUPABASE_ANON_KEY").getValue());
        verify(projects).requireManageable(7L, projectId);
        verify(events).publishEvent(new BuildVariables.Changed(projectId));
    }

    @Test
    void wrongUrlsKeysAndTokensAreRejectedBeforeAnythingIsStored() {
        assertThrows(SupabaseException.class, () -> service.connect(7L, projectId, "https://evil.example/" + REF, ANON, null));
        assertThrows(SupabaseException.class, () -> service.connect(7L, projectId, base + "/" + REF, "wrong-key", null));
        assertThrows(SupabaseException.class, () -> service.connect(7L, projectId, base + "/" + REF, ANON, "wrong-token"));
        assertTrue(connectionStore.isEmpty() && variableStore.isEmpty());
    }

    @Test
    void migrationsRunInNameOrderOnlyOnceAndChangesAfterApplyAreReported() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        project.write("supabase/migrations/20260101000002_policies.sql", "alter table todos enable row level security;")
                .write("supabase/migrations/20260101000001_todos.sql", "create table todos (id bigint primary key);")
                .write("supabase/migrations/notes.txt", "not a migration");

        assertEquals(List.of("PENDING", "PENDING"), statuses());
        SupabaseService.ApplyResult result = service.apply(7L, projectId);

        assertEquals(List.of("20260101000001_todos.sql", "20260101000002_policies.sql"), result.applied());
        assertEquals(List.of("create table todos (id bigint primary key);", "alter table todos enable row level security;"), executedSql);
        assertTrue(service.apply(7L, projectId).applied().isEmpty(), "applied migrations never run twice");
        project.write("supabase/migrations/20260101000001_todos.sql", "create table todos (id bigint primary key, title text);");
        assertEquals(List.of("CHANGED_AFTER_APPLY", "APPLIED"), statuses());
        assertEquals(2, executedSql.size());
    }

    @Test
    void aFailingMigrationStopsTheRestAndIsReported() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        project.write("supabase/migrations/1_a.sql", "select 1;").write("supabase/migrations/2_b.sql", "broken sql")
                .write("supabase/migrations/3_c.sql", "select 3;");
        failingSql = "broken sql";

        SupabaseService.ApplyResult result = service.apply(7L, projectId);

        assertEquals(List.of("1_a.sql"), result.applied());
        assertEquals("2_b.sql", result.failed());
        assertTrue(result.error().contains("syntax error"), result.error());
        assertEquals(List.of("3_c.sql"), result.remaining());
        assertEquals(List.of("APPLIED", "PENDING", "PENDING"), statuses());
    }

    @Test
    void applyingNeedsAToken() {
        service.connect(7L, projectId, base + "/" + REF, ANON, null);
        project.write("supabase/migrations/1_a.sql", "select 1;");

        assertThrows(WorkflowStageException.class, () -> service.apply(7L, projectId));
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void theOperatorInstanceCannotBeConnectedWhileItsSqlIsDisabled() {
        var operator = new SelfHostedSupabase(base + "/operator", "", "postgres", "");
        var guarded = new SupabaseService(projects, connections(), migrations(), variables(),
                new SupabaseClient(new ObjectMapper(), base), operator, cipher, project.files, events, new FakeTransactions(),
                "^http://127\\.0\\.0\\.1:\\d+/([a-z0-9]{20})$");

        var failure = assertThrows(SelfHostedSqlDisabledException.class,
                () -> guarded.connect(7L, projectId, base + "/operator", ANON, null));

        assertEquals("SELF_HOSTED_SQL_DISABLED", failure.code());
        assertTrue(connectionStore.isEmpty() && variableStore.isEmpty());
    }

    @Test
    void aSelfHostedApplyUsesTheExplicitDisabledCodeBeforeAnySqlIsSent() {
        connectionStore.put(projectId, SupabaseConnection.builder().projectId(projectId).projectRef("self-hosted")
                .url("https://operator.invalid").anonKey(ANON).mode(SupabaseConnection.Mode.SELF_HOSTED).build());
        project.write("supabase/migrations/1_a.sql", "select 1;");

        var failure = assertThrows(SelfHostedSqlDisabledException.class, () -> service.apply(7L, projectId));

        assertEquals("SELF_HOSTED_SQL_DISABLED", failure.code());
        assertTrue(executedSql.isEmpty());
        assertTrue(migrationStore.isEmpty());
    }

    @Test
    void disconnectingRemovesTheConnectionAndItsVariables() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);

        service.disconnect(7L, projectId);

        assertTrue(connectionStore.isEmpty());
        assertFalse(variableStore.containsKey("VITE_SUPABASE_URL"));
        assertFalse(service.status(7L, projectId).connected());
    }

    private List<String> statuses() {
        return service.migrations(7L, projectId).stream().map(SupabaseService.Migration::status).toList();
    }

    /* ── fake Supabase ───────────────────────────────────────────────────────── */

    private HttpServer fakeSupabase() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                int status;
                String body = "{}";
                if (path.equals("/" + REF + "/auth/v1/user")) {
                    // Like Supabase: the gateway rejects an unknown key (401); auth answers 403 without a session.
                    status = ANON.equals(exchange.getRequestHeaders().getFirst("apikey")) ? 403 : 401;
                } else if (path.equals("/v1/projects/" + REF)) {
                    status = ("Bearer " + TOKEN).equals(auth) ? 200 : 401;
                } else if (path.equals("/v1/projects/" + REF + "/database/query")) {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    String sql = new ObjectMapper().readTree(request).get("query").asText();
                    if (!("Bearer " + TOKEN).equals(auth)) status = 401;
                    else if (sql.equals(failingSql)) { status = 400; body = "{\"message\":\"ERROR: 42601: syntax error at or near \\\"broken\\\"\"}"; }
                    else { status = 201; executedSql.add(sql); body = "[]"; }
                } else {
                    status = 404;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            http.start();
            return http;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /* ── in-memory repositories ──────────────────────────────────────────────── */

    private SupabaseConnectionRepository connections() {
        SupabaseConnectionRepository repository = mock(SupabaseConnectionRepository.class);
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(connectionStore.get(call.<UUID>getArgument(0))));
        when(repository.save(any())).thenAnswer(call -> {
            SupabaseConnection connection = call.getArgument(0);
            connectionStore.put(connection.getProjectId(), connection);
            return connection;
        });
        org.mockito.Mockito.doAnswer(call -> connectionStore.remove(call.<UUID>getArgument(0))).when(repository).deleteById(any());
        return repository;
    }

    private SupabaseMigrationRepository migrations() {
        SupabaseMigrationRepository repository = mock(SupabaseMigrationRepository.class);
        when(repository.findByProjectIdOrderByNameAsc(any())).thenAnswer(call -> List.copyOf(migrationStore));
        when(repository.save(any())).thenAnswer(call -> {
            migrationStore.add(call.getArgument(0));
            return call.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(call -> { migrationStore.clear(); return null; }).when(repository).deleteByProjectId(any());
        return repository;
    }

    private ProjectEnvironmentVariableRepository variables() {
        ProjectEnvironmentVariableRepository repository = mock(ProjectEnvironmentVariableRepository.class);
        when(repository.findByProjectIdAndName(any(), anyString())).thenAnswer(call -> Optional.ofNullable(variableStore.get(call.<String>getArgument(1))));
        when(repository.save(any())).thenAnswer(call -> {
            ProjectEnvironmentVariable variable = call.getArgument(0);
            variableStore.put(variable.getName(), variable);
            return variable;
        });
        org.mockito.Mockito.doAnswer(call -> variableStore.remove(call.<ProjectEnvironmentVariable>getArgument(0).getName()))
                .when(repository).delete(any());
        return repository;
    }

    private static final class FakeTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
