package com.code.pal.project.supabase;

import com.code.pal.shared.config.DataCipher;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Disposable platform DB only; the remote provider is a deterministic in-memory callback. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
class SupabaseMigrationLedgerIT {
    private static AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private AtomicReference<Instant> time;
    private UUID project;
    private UUID epoch;
    private static final String REF = "abcdefghijklmnopqrst";

    @Configuration
    static class Services {}

    @BeforeAll
    static void open() {
        app = CodexJpaFixture.open(Services.class);
    }

    @AfterAll
    static void close() {
        if (app != null) app.close();
    }

    @BeforeEach
    void fixture() {
        jdbc = app.getBean(JdbcTemplate.class);
        transactions = app.getBean(PlatformTransactionManager.class);
        jdbc.update("delete from supabase_migration_attempts");
        jdbc.update("delete from supabase_apply_operations");
        jdbc.update("delete from project_supabase_migrations");
        jdbc.update("delete from project_supabase_connections");
        time = new AtomicReference<>(jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant());
        project = UUID.randomUUID();
        epoch = connection(project, REF);
    }

    @Test
    void twoInstancesCannotClaimTheSameTarget() throws Exception {
        var first = ledger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> first.execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "remote SQL cannot hold a platform transaction");
                assertEquals("select 1;", source.sql());
                calls.incrementAndGet();
                entered.countDown();
                await(release);
            }));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(SupabaseMigrationException.class,
                        () -> ledger().execute(7L, manifest(project, REF, epoch), "two", () -> {}, source -> fail("duplicate send")));
            } finally { release.countDown(); }
            assertEquals("COMPLETED", future.get(10, TimeUnit.SECONDS).state());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void twoProjectsSharingATargetStillSerialize() {
        crash("AFTER_CLAIM", manifest(project, REF, epoch), "one", source -> fail("not sent"));
        UUID other = UUID.randomUUID();
        UUID otherEpoch = connection(other, REF);
        assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, manifest(other, REF, otherEpoch), "two", () -> {}, source -> fail("foreign send")));
    }

    @Test
    void sameProjectCannotSwitchTargetsDuringAnActiveApply() {
        crash("AFTER_CLAIM", manifest(project, REF, epoch), "one", source -> fail("not sent"));
        assertThrows(SupabaseMigrationException.class, () -> new TransactionTemplate(transactions).execute(status -> {
            ledger().guardConnectionChange(project);
            return null;
        }));
        String other = "zyxwvutsrqponmlkjihg";
        jdbc.update("update project_supabase_connections set project_ref=?,url=? where project_id=?", other, url(other), project);
        assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, manifest(project, other, epoch), "two", () -> {}, source -> fail("new target send")));
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheBoundOperation() {
        var calls = new AtomicInteger();
        var spec = manifest(project, REF, epoch);
        var first = ledger().execute(7L, spec, "same", () -> {}, source -> calls.incrementAndGet());
        var second = ledger().execute(7L, spec, "same", () -> {}, source -> fail("duplicate send"));
        assertEquals(first, second);
        assertEquals(1, calls.get());
        assertEquals(1, jdbc.queryForObject("select count(*) from supabase_migration_attempts", Integer.class));
    }

    @Test
    void reusedKeyWithDifferentManifestConflicts() {
        ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> {});
        var changed = SupabaseMigrationManifest.create(project, target(REF), epoch,
                List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 2;")));
        var error = assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, changed, "same", () -> {}, source -> fail("changed send")));
        assertEquals("MIGRATION_IDEMPOTENCY_CONFLICT", error.code());
    }

    @Test
    void expiredPreparedOwnerCannotDispatchAfterTakeover() throws Exception {
        var first = ledger();
        var paused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        first.cut = phase -> {
            if (phase.equals("AFTER_CLAIM")) {
                paused.countDown();
                await(release);
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var old = pool.submit(() -> first.execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> fail("stale send")));
            try {
                assertTrue(paused.await(10, TimeUnit.SECONDS));
                time.updateAndGet(value -> value.plusSeconds(121));
                assertEquals("COMPLETED", ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> {}).state());
            } finally { release.countDown(); }
            old.get(10, TimeUnit.SECONDS);
            assertEquals(2L, jdbc.queryForObject("select fence from supabase_apply_operations", Long.class));
        }
    }

    @Test
    void expiredSendingLeaseNeverAuthorizesReplay() {
        crash("AFTER_SENDING", manifest(project, REF, epoch), "same", source -> fail("send not reached"));
        time.updateAndGet(value -> value.plusSeconds(121));
        assertEquals(1, ledger().recover(10));
        var outcome = ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> fail("unknown replay"));
        assertEquals("RECOVERY_REQUIRED", outcome.state());
        assertEquals("UNKNOWN", jdbc.queryForObject("select state from supabase_migration_attempts", String.class));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, manifest(project, REF, epoch), "different", () -> {}, source -> fail("new key replay")));
    }

    @Test
    void remoteCommitBeforeLocalSettlementStaysQuarantinedWithoutProof() {
        var calls = new AtomicInteger();
        crash("AFTER_REMOTE_SUCCESS", manifest(project, REF, epoch), "same", source -> calls.incrementAndGet());
        time.updateAndGet(value -> value.plusSeconds(121));
        ledger().recover(10);
        ledger().recover(10);
        assertEquals("RECOVERY_REQUIRED", ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {},
                source -> fail("committed SQL repeated")).state());
        assertEquals(1, calls.get());
        assertEquals(0, jdbc.queryForObject("select count(*) from project_supabase_migrations", Integer.class));
    }

    @Test
    void appliedResponseLossAndRepeatedReconciliationNeverExecuteAgain() {
        crash("AFTER_SETTLE", manifest(project, REF, epoch), "same", source -> {});
        time.updateAndGet(value -> value.plusSeconds(121));
        ledger().recover(10);
        ledger().recover(10);
        assertEquals("COMPLETED", ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> fail("settled replay")).state());
        assertEquals(1, jdbc.queryForObject("select count(*) from project_supabase_migrations", Integer.class));
    }

    @Test
    void staleOwnerCannotSettleAnotherAttemptsOutcome() {
        var first = ledger();
        first.cut = phase -> {
            if (phase.equals("AFTER_REMOTE_SUCCESS")) {
                time.updateAndGet(value -> value.plusSeconds(121));
                ledger().recover(10);
            }
        };
        var result = first.execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> {});
        assertEquals("RECOVERY_REQUIRED", result.state());
        assertEquals(0, jdbc.queryForObject("select count(*) from supabase_migration_attempts where state='APPLIED'", Integer.class));
    }

    @Test
    void unresolvedRowsBackOffWithoutStarvingPreparedRecovery() {
        crash("AFTER_SENDING", manifest(project, REF, epoch), "one", source -> fail("not sent"));
        UUID other = UUID.randomUUID();
        String ref = "zyxwvutsrqponmlkjihg";
        UUID otherEpoch = connection(other, ref);
        crash("AFTER_SETTLE", manifest(other, ref, otherEpoch), "two", source -> {});
        time.updateAndGet(value -> value.plusSeconds(121));
        assertEquals(1, ledger().recover(1));
        assertEquals(1, ledger().recover(1));
        assertEquals(0, ledger().recover(1));
        assertEquals("COMPLETED", ledger().execute(7L, manifest(other, ref, otherEpoch), "two", () -> {}, source -> fail("repeat")).state());
    }

    @Test
    void aCrashBeforeClaimHasNoAttemptAndCanRetry() {
        crash("BEFORE_CLAIM", manifest(project, REF, epoch), "same", source -> fail("not sent"));
        assertEquals(0, jdbc.queryForObject("select count(*) from supabase_apply_operations", Integer.class));
        assertEquals("COMPLETED", ledger().execute(7L, manifest(project, REF, epoch), "same", () -> {}, source -> {}).state());
    }

    @Test
    void changedAppliedChecksumBlocksLaterMigrations() {
        ledger().execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> {});
        var changed = SupabaseMigrationManifest.create(project, target(REF), epoch,
                List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 99;"), SupabaseMigrationManifest.Source.of("2_b.sql", "select 2;")));
        var error = assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, changed, "two", () -> {}, source -> fail("schema drift")));
        assertEquals("MIGRATION_APPLIED_SOURCE_CHANGED", error.code());
    }

    @Test
    void legacyUnboundHistoryNeedsExplicitAdoption() {
        jdbc.update("insert into project_supabase_migrations(id,project_id,name,checksum,applied_by,applied_at) values (?,?,?, ?,7,now())",
                UUID.randomUUID(), project, "1_a.sql", SupabaseService.checksum("select 1;"));
        var error = assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> fail("legacy rerun")));
        assertEquals("MIGRATION_LEGACY_UNBOUND", error.code());
    }

    @Test
    void encryptedSnapshotsCannotBeRewrittenOrResetAfterSending() {
        crash("AFTER_SENDING", manifest(project, REF, epoch), "same", source -> fail("not sent"));
        assertThrows(RuntimeException.class, () -> jdbc.update("update supabase_migration_attempts set checksum=?", "sha256:" + "b".repeat(64)));
        assertThrows(RuntimeException.class, () -> jdbc.update("update supabase_migration_attempts set state='PREPARED'"));
        String cipher = jdbc.queryForObject("select sql_cipher from supabase_migration_attempts", String.class);
        assertFalse(cipher.contains("select 1"));
    }

    @Test
    void revokedPermissionPreventsFurtherDispatchAfterPartialSuccess() {
        var spec = SupabaseMigrationManifest.create(project, target(REF), epoch, List.of(
                SupabaseMigrationManifest.Source.of("1_a.sql", "select 1;"), SupabaseMigrationManifest.Source.of("2_b.sql", "select 2;")));
        var calls = new AtomicInteger();
        var result = ledger().execute(7L, spec, "one", () -> { if (calls.get() > 0) throw new IllegalStateException("revoked"); },
                source -> calls.incrementAndGet());
        assertEquals("ABORTED", result.state());
        assertEquals(List.of("1_a.sql"), result.applied());
        assertEquals(1, calls.get());
    }

    @Test
    void connectionEpochBumpsOnlyUnderTheSharedGuard() {
        var ledger = ledger();
        assertThrows(IllegalStateException.class, () -> ledger.connectionChanged(project));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ledger.guardConnectionChange(project);
            ledger.connectionChanged(project);
        });
        assertNotEquals(epoch, ledger.connectionEpoch(project));
    }

    @Test
    void partialProviderFailureRetainsProofAndNeverReplaysTheUncertainFile() {
        var spec = SupabaseMigrationManifest.create(project, target(REF), epoch, List.of(
                SupabaseMigrationManifest.Source.of("1_a.sql", "select 1;"), SupabaseMigrationManifest.Source.of("2_b.sql", "select 2;"),
                SupabaseMigrationManifest.Source.of("3_c.sql", "select 3;")));
        var calls = new AtomicInteger();
        var result = ledger().execute(7L, spec, "one", () -> {}, source -> {
            if (calls.incrementAndGet() == 2) throw new SupabaseException("sensitive-provider-token SQL password");
        });
        assertEquals("RECOVERY_REQUIRED", result.state());
        assertEquals(List.of("1_a.sql"), result.applied());
        assertEquals("2_b.sql", result.failed());
        assertEquals(List.of("3_c.sql"), result.remaining());
        assertEquals("MIGRATION_OUTCOME_UNKNOWN", result.code());
        assertEquals(result, ledger().execute(7L, spec, "one", () -> {}, source -> fail("uncertain replay")));
        assertFalse(result.toString().contains("sensitive"));
        assertEquals(2, calls.get());
    }

    @Test
    void appliedTargetProofSurvivesAnotherProjectAliasAndCredentialEpoch() {
        ledger().execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> {});
        UUID alias = UUID.randomUUID();
        UUID aliasEpoch = connection(alias, REF);
        assertEquals("COMPLETED", ledger().execute(8L, manifest(alias, REF, aliasEpoch), "other", () -> {}, source -> fail("target SQL repeated")).state());
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger().connectionChanged(project));
        assertEquals("COMPLETED", ledger().execute(7L, manifest(project, REF, ledger().connectionEpoch(project)), "new-epoch", () -> {},
                source -> fail("credential rotation repeated SQL")).state());
    }

    @Test
    void sourceAndConnectionDriftBeforeDispatchAbortWithoutSending() {
        var instance = ledger();
        instance.cut = phase -> {
            if (phase.equals("AFTER_CLAIM")) jdbc.update("update project_supabase_connections set connection_epoch=? where project_id=?", UUID.randomUUID(), project);
        };
        assertEquals("ABORTED", instance.execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> fail("stale connection send")).state());
        var checks = new AtomicInteger();
        var spec = manifest(project, REF, ledger().connectionEpoch(project));
        assertEquals("ABORTED", ledger().execute(7L, spec, "two", () -> {
            if (checks.incrementAndGet() > 1) throw new SupabaseMigrationException("MIGRATION_MANIFEST_CHANGED");
        }, source -> fail("stale source send")).state());
        assertEquals(0, jdbc.queryForObject("select count(*) from supabase_migration_attempts where sent_at is not null", Integer.class));
    }

    @Test
    void legacyEvidenceCannotBeErasedByReconnectOrByAProjectAlias() {
        jdbc.update("insert into project_supabase_migrations(id,project_id,name,checksum,applied_by,applied_at) values (?,?,?, ?,7,now())",
                UUID.randomUUID(), project, "1_a.sql", SupabaseService.checksum("select 1;"));
        assertThrows(SupabaseMigrationException.class, () -> new TransactionTemplate(transactions).executeWithoutResult(status -> ledger().guardConnectionChange(project)));
        UUID other = UUID.randomUUID();
        UUID otherEpoch = connection(other, REF);
        assertEquals("MIGRATION_LEGACY_UNBOUND", assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, manifest(other, REF, otherEpoch), "alias", () -> {}, source -> fail("legacy alias replay"))).code());
    }

    @Test
    void pollingCannotReadAnotherActorOrProjectsOperation() {
        var done = ledger().execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> {});
        assertEquals(done, ledger().outcome(7L, project, done.operationId()));
        assertEquals(done, ledger().outcomeByKey(7L, project, "one"));
        assertThrows(SupabaseMigrationException.class, () -> ledger().outcome(8L, project, done.operationId()));
        assertThrows(SupabaseMigrationException.class, () -> ledger().outcome(7L, UUID.randomUUID(), done.operationId()));
        assertThrows(SupabaseMigrationException.class, () -> ledger().outcomeByKey(8L, project, "one"));
        assertThrows(SupabaseMigrationException.class, () -> ledger().outcomeByKey(7L, UUID.randomUUID(), "one"));
    }

    @Test
    void invalidKeysAndMissingEncryptionCannotClaimOrSend() {
        var spec = manifest(project, REF, epoch);
        assertEquals("MIGRATION_IDEMPOTENCY_KEY_REQUIRED", assertThrows(SupabaseMigrationException.class,
                () -> ledger().execute(7L, spec, "bad\nkey", () -> {}, source -> fail("bad-key send"))).code());
        var unavailable = new SupabaseMigrationLedger(jdbc, transactions, new DataCipher(""));
        assertEquals("MIGRATION_SNAPSHOT_ENCRYPTION_UNAVAILABLE", assertThrows(SupabaseMigrationException.class,
                () -> unavailable.execute(7L, spec, "one", () -> {}, source -> fail("unencrypted send"))).code());
        assertEquals(0, jdbc.queryForObject("select count(*) from supabase_apply_operations", Integer.class));
    }

    @Test
    void shortProjectAdmissionContentionDoesNotLoseAConfirmedRemoteSuccess() throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blocker = new AtomicReference<Future<?>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            try {
                var done = ledger().execute(7L, manifest(project, REF, epoch), "one", () -> {}, source -> {
                    blocker.set(pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,0))",
                                Boolean.class, "supabase-project:" + project)));
                        held.countDown();
                        await(release);
                    })));
                    await(held);
                });
                assertEquals("COMPLETED", done.state(), "row/owner proof is sufficient; an admission mutex alone cannot lose a successful response");
            } finally {
                release.countDown();
                if (blocker.get() != null) blocker.get().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private UUID connection(UUID id, String ref) {
        UUID generation = UUID.randomUUID();
        jdbc.update("insert into project_supabase_connections(project_id,project_ref,url,anon_key,mode,connected_by,created_at,updated_at,connection_epoch) "
                        + "values (?,?,?,'public','CLOUD',7,now(),now(),?)", id, ref, url(ref), generation);
        return generation;
    }

    private SupabaseMigrationManifest manifest(UUID id, String ref, UUID generation) {
        return SupabaseMigrationManifest.create(id, target(ref), generation, List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 1;")));
    }

    private static String target(String ref) { return "supabase-cloud:" + ref; }
    private static String url(String ref) { return "https://" + ref + ".supabase.co"; }

    private TestLedger ledger() { return new TestLedger(); }

    private void crash(String phase, SupabaseMigrationManifest spec, String key, Consumer<SupabaseMigrationManifest.Source> send) {
        var instance = ledger();
        instance.cut = point -> { if (point.equals(phase)) throw new ProcessLoss(); };
        assertThrows(ProcessLoss.class, () -> instance.execute(7L, spec, key, () -> {}, send));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static final class ProcessLoss extends Error {}

    private final class TestLedger extends SupabaseMigrationLedger {
        Consumer<String> cut = phase -> {};
        TestLedger() {
            super(SupabaseMigrationLedgerIT.this.jdbc, SupabaseMigrationLedgerIT.this.transactions, new DataCipher("synthetic-ledger-key-0123456789"));
        }
        @Override protected Instant now() { return time.get(); }
        @Override protected void checkpoint(String phase) { cut.accept(phase); }
    }
}
