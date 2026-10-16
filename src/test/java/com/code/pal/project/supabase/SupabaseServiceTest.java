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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Against a fake Supabase (management API + project API) on loopback. */
class SupabaseServiceTest {
    private static final String REF = "abcdefghijklmnopqrst";
    private static final String OTHER_REF = "bbbbbbbbbbbbbbbbbbbb";
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
    private final SupabaseMigrationLedger ledger = mock(SupabaseMigrationLedger.class);
    private final SupabaseService service = new SupabaseService(projects, connections(), migrations(), variables(),
            new SupabaseClient(new ObjectMapper(), base), new SelfHostedSupabase("", "", "postgres", ""), cipher, project.files, events, new FakeTransactions(), ledger,
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
        verify(projects, org.mockito.Mockito.times(2)).requireManageable(7L, projectId);   // again inside the write transaction
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
    void reviewBindsSortedExactSourcesWithoutDispatch() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        project.write("supabase/migrations/20260101000002_policies.sql", "alter table todos enable row level security;")
                .write("supabase/migrations/20260101000001_todos.sql", "create table todos (id bigint primary key);")
                .write("supabase/migrations/notes.txt", "not a migration");

        assertEquals(List.of("PENDING", "PENDING"), statuses());
        installLedger();
        var review = service.review(7L, projectId);
        assertEquals(List.of("20260101000001_todos.sql", "20260101000002_policies.sql"), review.migrations().stream().map(SupabaseService.Migration::name).toList());
        project.write("supabase/migrations/20260101000001_todos.sql", "create table todos (id bigint primary key, title text);");
        assertNotEquals(review.expectedManifestHash(), service.review(7L, projectId).expectedManifestHash());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void legacyApplyRefusesWithoutDispatch() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        project.write("supabase/migrations/1_a.sql", "select 1;").write("supabase/migrations/2_b.sql", "broken sql")
                .write("supabase/migrations/3_c.sql", "select 3;");
        failingSql = "broken sql";

        var failure = assertThrows(SupabaseMigrationException.class, () -> service.apply(7L, projectId));
        assertEquals("MIGRATION_MANIFEST_REQUIRED", failure.code());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void sourceOrConnectionChangeRejectsTheApprovedManifest() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        var ledger = installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;");
        var reviewed = service.review(7L, projectId);
        project.write("supabase/migrations/1_a.sql", "select 2;");
        assertEquals("MIGRATION_MANIFEST_CHANGED", assertThrows(SupabaseMigrationException.class,
                () -> service.apply(7L, projectId, reviewed.expectedManifestHash(), "one")).code());
        project.write("supabase/migrations/1_a.sql", "select 1;");
        when(ledger.connectionEpoch(projectId)).thenReturn(UUID.randomUUID());
        assertEquals("MIGRATION_MANIFEST_CHANGED", assertThrows(SupabaseMigrationException.class,
                () -> service.apply(7L, projectId, reviewed.expectedManifestHash(), "two")).code());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void reconnectDisconnectAndRevokedPermissionPreventFurtherDispatch() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        var ledger = installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;");
        var reviewed = service.review(7L, projectId);
        doAnswer(call -> {
            Runnable check = call.getArgument(3);
            UUID changedEpoch = UUID.randomUUID();
            when(ledger.connectionEpoch(projectId)).thenReturn(changedEpoch);
            assertThrows(SupabaseMigrationException.class, check::run);
            connectionStore.clear();
            assertThrows(WorkflowStageException.class, check::run);
            doThrow(new WorkflowStageException("revoked")).when(projects).requireManageable(7L, projectId);
            assertThrows(WorkflowStageException.class, check::run);
            return new SupabaseMigrationLedger.Outcome(UUID.randomUUID(), "ABORTED", List.of(), "1_a.sql", "MIGRATION_DISPATCH_STOPPED", List.of());
        }).when(ledger).execute(eq(7L), any(), eq("one"), any(), any());
        assertEquals("ABORTED", service.apply(7L, projectId, reviewed.expectedManifestHash(), "one").state());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void partialSuccessRetainsEveryOutcomeAndStopsRemainingFiles() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        var ledger = installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;").write("supabase/migrations/2_b.sql", "select 2;")
                .write("supabase/migrations/3_c.sql", "select 3;");
        UUID operation = UUID.randomUUID();
        when(ledger.execute(eq(7L), any(), eq("one"), any(), any())).thenReturn(new SupabaseMigrationLedger.Outcome(
                operation, "RECOVERY_REQUIRED", List.of("1_a.sql"), "2_b.sql", "MIGRATION_OUTCOME_UNKNOWN", List.of("3_c.sql")));
        var result = service.apply(7L, projectId, service.review(7L, projectId).expectedManifestHash(), "one");
        assertEquals(operation, result.operationId());
        assertEquals("RECOVERY_REQUIRED", result.state());
        assertEquals(List.of("1_a.sql"), result.applied());
        assertEquals("2_b.sql", result.failed());
        assertEquals("MIGRATION_OUTCOME_UNKNOWN", result.error());
        assertEquals(List.of("3_c.sql"), result.remaining());
    }

    @Test
    void changedAppliedChecksumBlocksLaterMigrations() { assertProofRefusal("MIGRATION_APPLIED_SOURCE_CHANGED"); }

    @Test
    void legacyUnboundHistoryNeedsExplicitAdoption() { assertProofRefusal("MIGRATION_LEGACY_UNBOUND"); }

    @Test
    void invalidApprovalNeverFallsBackToLegacySql() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        installLedger();
        assertThrows(SupabaseMigrationException.class, () -> service.apply(7L, projectId, null, "one"));
        assertThrows(SupabaseMigrationException.class, () -> service.apply(7L, projectId, "not-a-digest", "one"));
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void newApplyDispatchesOnlyTheLedgerSnapshot() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        var ledger = installLedger();
        project.write("supabase/migrations/1_a.sql", "select 'approved';");
        doAnswer(call -> {
            Runnable check = call.getArgument(3);
            check.run();
            Consumer<SupabaseMigrationManifest.Source> send = call.getArgument(4);
            var snapshot = call.<SupabaseMigrationManifest>getArgument(1).sources().getFirst();
            send.accept(snapshot);
            return new SupabaseMigrationLedger.Outcome(UUID.randomUUID(), "COMPLETED", List.of(snapshot.name()), null, null, List.of());
        }).when(ledger).execute(eq(7L), any(), eq("one"), any(), any());
        assertEquals("COMPLETED", service.apply(7L, projectId, service.review(7L, projectId).expectedManifestHash(), "one").state());
        assertEquals(List.of("select 'approved';"), executedSql);
    }

    @Test
    void malformedUtf8CannotBeSilentlyReencodedForApproval() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        installLedger();
        project.files.writeBytes(projectId, "supabase/migrations/1_a.sql", new byte[] {(byte) 0xc3, (byte) 0x28});
        assertEquals("MIGRATION_INVALID_UTF8", assertThrows(SupabaseMigrationException.class, () -> service.review(7L, projectId)).code());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void lostResponseCanBePolledByRequestKeyWithoutRereadingChangedSources() {
        var ledger = installLedger();
        UUID operation = UUID.randomUUID();
        when(ledger.outcomeByKey(7L, projectId, "lost-response")).thenReturn(new SupabaseMigrationLedger.Outcome(
                operation, "RECOVERY_REQUIRED", List.of(), "1_a.sql", "MIGRATION_OUTCOME_UNKNOWN", List.of("2_b.sql")));
        project.files.writeBytes(projectId, "supabase/migrations/1_a.sql", new byte[] {(byte) 0xff});
        assertEquals(operation, service.operationByKey(7L, projectId, "lost-response").operationId());
        assertTrue(executedSql.isEmpty());
        verify(projects).requireManageable(7L, projectId);
    }

    private SupabaseMigrationLedger installLedger() {
        UUID epoch = UUID.randomUUID();
        when(ledger.connectionEpoch(projectId)).thenReturn(epoch);
        return ledger;
    }

    private void assertProofRefusal(String code) {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        var ledger = installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;");
        when(ledger.execute(eq(7L), any(), eq("one"), any(), any())).thenThrow(new SupabaseMigrationException(code));
        assertEquals(code, assertThrows(SupabaseMigrationException.class,
                () -> service.apply(7L, projectId, service.review(7L, projectId).expectedManifestHash(), "one")).code());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void applyingNeedsAToken() {
        service.connect(7L, projectId, base + "/" + REF, ANON, null);
        project.write("supabase/migrations/1_a.sql", "select 1;");

        assertThrows(WorkflowStageException.class, () -> service.apply(7L, projectId));
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void connectingAndDisconnectingFenceTheLedgerAndRotateTheApprovalEpoch() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        service.disconnect(7L, projectId);

        var order = inOrder(ledger);
        order.verify(ledger).guardConnectionChange(projectId);
        order.verify(ledger).connectionChanged(projectId);
        order.verify(ledger).guardConnectionChange(projectId);
        order.verify(ledger).connectionChanged(projectId);
    }

    @Test
    void aReconnectDuringAnActiveApplyStoresNothing() {
        doThrow(new SupabaseMigrationException("MIGRATION_TARGET_BUSY")).when(ledger).guardConnectionChange(projectId);

        assertThrows(SupabaseMigrationException.class, () -> service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN));

        assertTrue(connectionStore.isEmpty() && variableStore.isEmpty());
        verify(ledger, never()).connectionChanged(any());
    }

    @Test
    void theOperatorInstanceCannotBeConnectedWhileItsSqlIsDisabled() {
        var operator = new SelfHostedSupabase(base + "/operator", "", "postgres", "");
        var guarded = new SupabaseService(projects, connections(), migrations(), variables(),
                new SupabaseClient(new ObjectMapper(), base), operator, cipher, project.files, events, new FakeTransactions(), ledger,
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
    void switchingProjectsWithoutANewTokenDropsTheOldTokenButAReconnectKeepsIt() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        assertTrue(service.connect(7L, projectId, base + "/" + REF, ANON, null).migrationsEnabled(), "same project keeps its token");

        var switched = service.connect(7L, projectId, base + "/" + OTHER_REF, ANON, null);

        assertFalse(switched.migrationsEnabled(), "the old project's token gives no SQL authority on the new one");
        assertNull(connectionStore.get(projectId).getAccessTokenCipher());
    }

    @Test
    void disconnectingRemovesTheConnectionAndItsVariables() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);

        service.disconnect(7L, projectId);

        assertTrue(connectionStore.isEmpty());
        assertFalse(variableStore.containsKey("VITE_SUPABASE_URL"));
        assertFalse(service.status(7L, projectId).connected());
    }

    @Test
    void ownerExitMetadataDoesNotRequireSqlCredentialsOrAnEnabledOperator() {
        connectionStore.put(projectId, SupabaseConnection.builder().projectId(projectId).projectRef("self-hosted")
                .url("https://operator.invalid").anonKey(ANON).mode(SupabaseConnection.Mode.SELF_HOSTED).build());
        UUID epoch = UUID.randomUUID();
        var review = new SupabaseOwnerAttestations.LegacyReview(projectId, "operator-target", epoch, "review", List.of());
        when(ledger.legacyReview(eq(7L), eq(projectId), any())).thenReturn(review);
        assertEquals(review, service.legacyReview(7L, projectId));
        assertTrue(executedSql.isEmpty());
        verify(projects).requireManageable(7L, projectId);
    }

    @Test
    void ownerExitRequiresManagePermissionBeforeReadingOrWritingAudit() {
        doThrow(new WorkflowStageException("revoked")).when(projects).requireManageable(7L, projectId);
        assertThrows(WorkflowStageException.class, () -> service.legacyReview(7L, projectId));
        assertThrows(WorkflowStageException.class, () -> service.adoptLegacy(7L, projectId, "review", "one", true));
        UUID operation = UUID.randomUUID();
        assertThrows(WorkflowStageException.class, () -> service.recoveryReview(7L, projectId, operation));
        assertThrows(WorkflowStageException.class, () -> service.resolveRecovery(7L, projectId, operation, "review", "one", List.of(), true));
        verify(ledger, never()).adoptLegacy(any(), any(), any(), any(), eq(true), any());
        assertTrue(executedSql.isEmpty());
    }

    @Test
    void attestedOutcomesAreNotMappedIntoVerifiedAppliedFiles() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;");
        UUID operation = UUID.randomUUID();
        when(ledger.execute(eq(7L), any(), eq("one"), any(), any())).thenReturn(new SupabaseMigrationLedger.Outcome(
                operation, "COMPLETED_ATTESTED", List.of(), null, null, List.of(), List.of("1_a.sql"), List.of()));
        var result = service.apply(7L, projectId, service.review(7L, projectId).expectedManifestHash(), "one");
        assertEquals("COMPLETED_ATTESTED", result.state());
        assertTrue(result.applied().isEmpty());
        assertEquals(List.of("1_a.sql"), result.ownerAttestedApplied());
    }

    @Test
    void reviewedAttestedFilesAreLabelledWithoutALegacyProjection() {
        service.connect(7L, projectId, base + "/" + REF, ANON, TOKEN);
        installLedger();
        project.write("supabase/migrations/1_a.sql", "select 1;");
        var proof = new SupabaseMigrationEvidence.Applied(SupabaseService.checksum("select 1;"), UUID.randomUUID());
        when(ledger.migrationEvidence("supabase-cloud:" + REF)).thenReturn(Map.of("1_a.sql", proof));
        assertEquals("OWNER_ATTESTED_APPLIED", service.review(7L, projectId).migrations().getFirst().status());
        assertEquals(List.of("OWNER_ATTESTED_APPLIED"), statuses());
        assertTrue(executedSql.isEmpty());
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
                if (path.equals("/" + REF + "/auth/v1/user") || path.equals("/" + OTHER_REF + "/auth/v1/user")) {
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
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    /* ── in-memory repositories ──────────────────────────────────────────────── */

    private SupabaseConnectionRepository connections() {
        SupabaseConnectionRepository repository = mock(SupabaseConnectionRepository.class);
        when(repository.findById(any())).thenAnswer(call -> Optional.ofNullable(connectionStore.get(call.<UUID>getArgument(0))));
        org.mockito.stubbing.Answer<SupabaseConnection> store = call -> {
            SupabaseConnection connection = call.getArgument(0);
            connectionStore.put(connection.getProjectId(), connection);
            return connection;
        };
        when(repository.save(any())).thenAnswer(store);
        when(repository.saveAndFlush(any())).thenAnswer(store);
        doAnswer(call -> connectionStore.remove(call.<UUID>getArgument(0))).when(repository).deleteById(any());
        return repository;
    }

    private SupabaseMigrationRepository migrations() {
        SupabaseMigrationRepository repository = mock(SupabaseMigrationRepository.class);
        when(repository.findByProjectIdOrderByNameAsc(any())).thenAnswer(call -> List.copyOf(migrationStore));
        when(repository.save(any())).thenAnswer(call -> {
            migrationStore.add(call.getArgument(0));
            return call.getArgument(0);
        });
        doAnswer(call -> {
            migrationStore.clear();
            return null;
        }).when(repository).deleteByProjectId(any());
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
        doAnswer(call -> variableStore.remove(call.<ProjectEnvironmentVariable>getArgument(0).getName()))
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
