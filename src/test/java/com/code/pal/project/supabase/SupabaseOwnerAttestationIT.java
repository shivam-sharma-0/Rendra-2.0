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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated platform schema; remote effects are callbacks, never actual Supabase SQL. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
class SupabaseOwnerAttestationIT {
    @Configuration
    static class Services {}

    private static AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactions;
    private AtomicReference<Instant> time;
    private UUID project;
    private String ref;

    @BeforeAll
    static void open() { app = CodexJpaFixture.open(Services.class); }

    @AfterAll
    static void close() { if (app != null) app.close(); }

    @BeforeEach
    void fixture() {
        jdbc = app.getBean(JdbcTemplate.class);
        transactions = app.getBean(PlatformTransactionManager.class);
        time = new AtomicReference<>(jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant());
        project = UUID.randomUUID();
        ref = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        connection(project);
        // Append-only audit rows are deliberately never cleared between methods; each target is unique.
    }

    @Test
    void legacyAdoptionIsAttestedNeverVerifiedAndUnblocksConnectionChanges() {
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var review = ledger.legacyReview(7L, project, () -> {});
        UUID epoch = ledger.connectionEpoch(project);
        var receipt = ledger.adoptLegacy(7L, project, review.hash(), "adopt", true, () -> {});
        assertEquals("OWNER_ATTESTED", receipt.evidence());
        assertEquals(7L, receipt.attestedBy());
        assertEquals(List.of("1_a.sql"), receipt.files().stream().map(SupabaseOwnerAttestations.AttestedFile::name).toList());
        assertNotEquals(epoch, ledger.connectionEpoch(project));
        new TransactionTemplate(transactions).executeWithoutResult(status -> ledger.guardConnectionChange(project));
        var done = ledger.execute(7L, manifest(), "fresh", () -> {}, source -> fail("attested SQL repeated"));
        assertEquals("COMPLETED_ATTESTED", done.state());
        assertTrue(done.applied().isEmpty());
        assertEquals(List.of("1_a.sql"), done.ownerAttestedApplied());
        assertEquals(0, count("supabase_migration_attempts"));
    }

    @Test
    void changedLegacyListAndConnectionInvalidateTheOwnerReview() {
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var first = ledger.legacyReview(7L, project, () -> {});
        legacy("2_b.sql", "select 2;");
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, first.hash(), "one", true, () -> {}));
        var second = ledger.legacyReview(7L, project, () -> {});
        jdbc.update("update project_supabase_connections set connection_epoch=? where project_id=?", UUID.randomUUID(), project);
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, second.hash(), "two", true, () -> {}));
        assertEquals(0, count("supabase_owner_attestations"));
    }

    @Test
    void attestationRetryReturnsTheSameReceiptWithoutRotatingAgain() {
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var review = ledger.legacyReview(7L, project, () -> {});
        var first = ledger.adoptLegacy(7L, project, review.hash(), "same", true, () -> {});
        UUID epoch = ledger.connectionEpoch(project);
        assertEquals(first, ledger().adoptLegacy(7L, project, review.hash(), "same", true, () -> {}));
        assertEquals(epoch, ledger.connectionEpoch(project));
        assertEquals(1, count("supabase_owner_attestations"));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.adoptLegacy(7L, project, "sha256:" + "f".repeat(64), "same", true, () -> {}));
    }

    @Test
    void unconfirmedOrConcurrentTargetAdoptionCannotWriteAudit() {
        UUID alias = UUID.randomUUID();
        connection(alias);
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var review = ledger.legacyReview(7L, project, () -> {});
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, review.hash(), "one", false, () -> {}));
        jdbc.update("insert into supabase_apply_operations(id,project_id,target_key,connection_epoch,manifest_hash,idempotency_key,approved_by,state,"
                        + "owner_id,fence,lease_expires_at) values (?,?,?,?,?,'active',8,'PREPARED',?,1,?)",
                UUID.randomUUID(), alias, target(), ledger.connectionEpoch(alias), "sha256:" + "a".repeat(64), UUID.randomUUID(), Timestamp.from(time.get().plusSeconds(120)));
        assertThrows(SupabaseMigrationException.class, () -> ledger.adoptLegacy(7L, project, review.hash(), "two", true, () -> {}));
        assertEquals(0, count("supabase_owner_attestations"));
    }

    @Test
    void resolvedAppliedKeepsUnknownEvidenceAndRequiresFreshApproval() {
        var ledger = unknown();
        var old = manifest();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        var receipt = ledger.resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "APPLIED"), true, () -> {});
        UUID resolvedEpoch = ledger.connectionEpoch(project);
        assertEquals(receipt, ledger().resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "APPLIED"), true, () -> {}));
        assertEquals(resolvedEpoch, ledger.connectionEpoch(project));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "NOT_APPLIED"), true, () -> {}));
        var result = ledger.outcomeByKey(7L, project, "build");
        assertEquals("RESOLVED_ATTESTED", result.state());
        assertTrue(result.applied().isEmpty());
        assertEquals(List.of("1_a.sql"), result.ownerAttestedApplied());
        assertEquals("UNKNOWN", jdbc.queryForObject("select state from supabase_migration_attempts where operation_id=?", String.class, operation()));
        assertNotEquals(old.hash(), manifest().hash());
        assertThrows(SupabaseMigrationException.class, () -> ledger.execute(7L, manifest(), "build", () -> {}, source -> fail("old-key replay")));
        assertEquals("COMPLETED_ATTESTED", ledger.execute(7L, manifest(), "new", () -> {}, source -> fail("attested replay")).state());
    }

    @Test
    void notAppliedResolutionDoesNotExecuteAndFreshApplyCanSendOnce() {
        var ledger = unknown();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        var receipt = ledger.resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "NOT_APPLIED"), true, () -> {});
        assertEquals("NOT_APPLIED", receipt.files().getFirst().decision());
        var result = ledger.outcomeByKey(7L, project, "build");
        assertEquals(List.of("1_a.sql"), result.ownerAttestedNotApplied());
        assertEquals(List.of("1_a.sql"), result.remaining());
        var calls = new AtomicInteger();
        assertEquals("COMPLETED", ledger.execute(7L, manifest(), "fresh-approval", () -> {}, source -> calls.incrementAndGet()).state());
        ledger.execute(7L, manifest(), "fresh-approval", () -> {}, source -> fail("duplicate retry"));
        assertEquals(1, calls.get());
    }

    @Test
    void liveLeaseMissingQuiescenceAndWrongChoicesCannotResolve() {
        var ledger = unknownWithoutExpiry();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "live", decisions(review, "APPLIED"), true, () -> {}));
        time.updateAndGet(value -> value.plusSeconds(121));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "unconfirmed", decisions(review, "APPLIED"), false, () -> {}));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "missing", List.of(), true, () -> {}));
        var forged = List.of(new SupabaseOwnerAttestations.ResolutionDecision(UUID.randomUUID(), "APPLIED"));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "foreign", forged, true, () -> {}));
        assertEquals(0, count("supabase_owner_attestations"));
    }

    @Test
    void auditAndResolvedStateAreAtomicAndAppendOnly() {
        var ledger = unknown();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        ledger.cut = phase -> { if (phase.equals("AFTER_OWNER_ATTESTATION")) throw new ProcessLoss(); };
        assertThrows(ProcessLoss.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "one", decisions(review, "APPLIED"), true, () -> {}));
        assertEquals(0, count("supabase_owner_attestations"));
        assertEquals("RECOVERY_REQUIRED", ledger.outcomeByKey(7L, project, "build").state());
        ledger.cut = phase -> {};
        var receipt = ledger.resolveRecovery(7L, project, operation(), review.hash(), "one", decisions(review, "APPLIED"), true, () -> {});
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from supabase_owner_attestations where id=?", receipt.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update("update supabase_owner_attestations set attested_by=9 where id=?", receipt.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from supabase_owner_attested_files where attestation_id=?", receipt.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update("update supabase_owner_attested_files set decision='NOT_APPLIED' where attestation_id=?", receipt.id()));
        assertThrows(RuntimeException.class, () -> jdbc.update("insert into supabase_owner_attested_files(attestation_id,ordinal,name,checksum,decision) "
                + "values (?,1,'extra.sql',?,'APPLIED')", receipt.id(), SupabaseService.checksum("select 2;")));
    }

    @Test
    void committedResponseLossRetriesWithoutAnotherAuditOrEpoch() {
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var review = ledger.legacyReview(7L, project, () -> {});
        ledger.cut = phase -> { if (phase.equals("AFTER_OWNER_ATTESTATION_COMMIT")) throw new ProcessLoss(); };
        assertThrows(ProcessLoss.class, () -> ledger.adoptLegacy(7L, project, review.hash(), "one", true, () -> {}));
        UUID epoch = ledger.connectionEpoch(project);
        ledger().adoptLegacy(7L, project, review.hash(), "one", true, () -> {});
        assertEquals(1, count("supabase_owner_attestations"));
        assertEquals(epoch, ledger.connectionEpoch(project));
    }

    @Test
    void staleSenderCannotExecuteAfterOwnerResolution() throws Exception {
        var ledger = ledger();
        var paused = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ledger.cut = phase -> {
            if (phase.equals("AFTER_SENDING")) {
                paused.countDown();
                await(release);
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var old = pool.submit(() -> ledger.execute(7L, manifest(), "build", () -> {}, source -> fail("resolved stale sender")));
            try {
                assertTrue(paused.await(10, TimeUnit.SECONDS));
                time.updateAndGet(value -> value.plusSeconds(121));
                var next = ledger();
                next.recover(100);
                var review = next.recoveryReview(7L, project, operation(), () -> {});
                next.resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "NOT_APPLIED"), true, () -> {});
            } finally {
                release.countDown();
            }
            assertEquals("RESOLVED_ATTESTED", old.get(10, TimeUnit.SECONDS).state());
        }
    }

    @Test
    void targetEpochsRotateAndOldOutcomeProvenanceDoesNotChangeLater() {
        UUID alias = UUID.randomUUID();
        connection(alias);
        var ledger = ledger();
        UUID aliasEpoch = ledger.connectionEpoch(alias);
        var before = ledger.execute(7L, manifest(), "old-verified", () -> {}, source -> {});
        legacy("2_b.sql", "select 2;");
        var review = ledger.legacyReview(7L, project, () -> {});
        ledger.adoptLegacy(7L, project, review.hash(), "adopt", true, () -> {});
        assertNotEquals(aliasEpoch, ledger.connectionEpoch(alias));
        assertEquals(before, ledger.outcomeByKey(7L, project, "old-verified"));
    }

    @Test
    void revokedPermissionAndForeignProjectCannotAttest() {
        var ledger = unknown();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        UUID other = UUID.randomUUID();
        connection(other);
        assertThrows(SupabaseMigrationException.class, () -> ledger.recoveryReview(7L, other, operation(), () -> {}));
        assertThrows(IllegalStateException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "revoked", decisions(review, "APPLIED"), true,
                        () -> { throw new IllegalStateException("revoked"); }));
        assertEquals(0, count("supabase_owner_attestations"));
    }

    @Test
    void twoOwnersCannotResolveTheSameTargetConcurrently() throws Exception {
        var ledger = unknown();
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ledger.cut = phase -> {
            if (phase.equals("AFTER_OWNER_ATTESTATION")) {
                entered.countDown();
                await(release);
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "one", decisions(review, "APPLIED"), true, () -> {}));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertThrows(SupabaseMigrationException.class,
                        () -> ledger().resolveRecovery(8L, project, operation(), review.hash(), "two", decisions(review, "NOT_APPLIED"), true, () -> {}));
            } finally {
                release.countDown();
            }
            assertEquals("OWNER_ATTESTED", first.get(10, TimeUnit.SECONDS).evidence());
            assertEquals(1, count("supabase_owner_attestations"));
        }
    }

    @Test
    void housekeepingDoesNotInvalidateAnUnchangedOwnerReview() {
        var ledger = unknown();
        var first = ledger.recoveryReview(7L, project, operation(), () -> {});
        time.updateAndGet(value -> value.plusSeconds(301));
        ledger.recover(100);
        var next = ledger.recoveryReview(7L, project, operation(), () -> {});
        assertEquals(first.hash(), next.hash());
        assertEquals(first.fence(), next.fence());
        ledger.resolveRecovery(7L, project, operation(), first.hash(), "resolve", decisions(first, "APPLIED"), true, () -> {});
        assertNotNull(ledger.recoveryReview(7L, project, operation(), () -> {}).resolution());
    }

    @Test
    void resolutionRequiresADurableDeclarationAndCannotFalsifyVerifiedSteps() {
        var ledger = unknown();
        assertThrows(RuntimeException.class, () -> jdbc.update("update supabase_apply_operations set state='RESOLVED_ATTESTED',fence=fence+1,updated_at=? where id=?",
                Timestamp.from(time.get()), operation()));
        UUID verified = UUID.randomUUID();
        // The only selectable resolution set comes from actual UNKNOWN rows, not client-supplied file names.
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        var extra = new ArrayList<>(decisions(review, "APPLIED"));
        extra.add(new SupabaseOwnerAttestations.ResolutionDecision(verified, "NOT_APPLIED"));
        assertThrows(SupabaseMigrationException.class, () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "forged", extra, true, () -> {}));
        assertEquals(0, count("supabase_owner_attestations"));
    }

    @Test
    void verifiedStepsAndUnsentFilesKeepTheirActualOutcomesAfterResolution() {
        var ledger = ledger();
        var sources = List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 1;"),
                SupabaseMigrationManifest.Source.of("2_b.sql", "select 2;"), SupabaseMigrationManifest.Source.of("3_c.sql", "select 3;"));
        var spec = SupabaseMigrationManifest.create(project, target(), ledger.connectionEpoch(project), sources);
        var calls = new AtomicInteger();
        ledger.execute(7L, spec, "build", () -> {}, source -> {
            calls.incrementAndGet();
            if (source.name().equals("2_b.sql")) throw new SupabaseException("uncertain");
        });
        time.updateAndGet(value -> value.plusSeconds(121));
        var review = ledger.recoveryReview(7L, project, operation(), () -> {});
        UUID proved = jdbc.queryForObject("select id from supabase_migration_attempts where operation_id=? and name='1_a.sql'", UUID.class, operation());
        var invalid = new ArrayList<>(decisions(review, "NOT_APPLIED"));
        invalid.add(new SupabaseOwnerAttestations.ResolutionDecision(proved, "NOT_APPLIED"));
        assertThrows(SupabaseMigrationException.class,
                () -> ledger.resolveRecovery(7L, project, operation(), review.hash(), "invalid", invalid, true, () -> {}));
        ledger.resolveRecovery(7L, project, operation(), review.hash(), "resolve", decisions(review, "NOT_APPLIED"), true, () -> {});
        var result = ledger.outcomeByKey(7L, project, "build");
        assertEquals(List.of("1_a.sql"), result.applied());
        assertEquals(List.of("2_b.sql"), result.ownerAttestedNotApplied());
        assertEquals(List.of("2_b.sql", "3_c.sql"), result.remaining());
        assertEquals(2, calls.get(), "resolution performs no remote operation");
        var fresh = SupabaseMigrationManifest.create(project, target(), ledger.connectionEpoch(project), sources);
        ledger.execute(7L, fresh, "fresh", () -> {}, source -> {
            assertNotEquals("1_a.sql", source.name());
            calls.incrementAndGet();
        });
        assertEquals(4, calls.get());
    }

    @Test
    void adoptedAuditSurvivesDisconnectAndItsIdempotentRetry() {
        legacy("1_a.sql", "select 1;");
        var ledger = ledger();
        var review = ledger.legacyReview(7L, project, () -> {});
        var receipt = ledger.adoptLegacy(7L, project, review.hash(), "adopt", true, () -> {});
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            ledger.guardConnectionChange(project);
            jdbc.update("delete from project_supabase_migrations where project_id=?", project);
            jdbc.update("delete from project_supabase_connections where project_id=?", project);
            ledger.connectionChanged(project);
        });
        assertEquals(receipt, ledger.adoptLegacy(7L, project, review.hash(), "adopt", true, () -> {}));
        assertEquals(1, count("supabase_owner_attestations"));
    }

    private TestLedger unknown() {
        var ledger = unknownWithoutExpiry();
        time.updateAndGet(value -> value.plusSeconds(121));
        return ledger;
    }

    private TestLedger unknownWithoutExpiry() {
        var ledger = ledger();
        ledger.execute(7L, manifest(), "build", () -> {}, source -> { throw new SupabaseException("unknown remote outcome"); });
        return ledger;
    }

    private List<SupabaseOwnerAttestations.ResolutionDecision> decisions(SupabaseOwnerAttestations.RecoveryReview review, String decision) {
        return review.unknown().stream().map(step -> new SupabaseOwnerAttestations.ResolutionDecision(step.attemptId(), decision)).toList();
    }

    private UUID operation() {
        return jdbc.queryForObject("select id from supabase_apply_operations where project_id=? and idempotency_key='build'", UUID.class, project);
    }

    private int count(String table) {
        // Only compile-time test table constants reach this helper; fixture's isolated schema owns every row.
        if (table.equals("supabase_migration_attempts")) {
            return jdbc.queryForObject("select count(*) from supabase_migration_attempts a join supabase_apply_operations o on o.id=a.operation_id "
                    + "where o.project_id=?", Integer.class, project);
        }
        return jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Integer.class, project);
    }

    private void legacy(String name, String sql) {
        jdbc.update("insert into project_supabase_migrations(id,project_id,name,checksum,applied_by,applied_at) values (?,?,?,?,7,now())",
                UUID.randomUUID(), project, name, SupabaseService.checksum(sql));
    }

    private void connection(UUID id) {
        jdbc.update("insert into project_supabase_connections(project_id,project_ref,url,anon_key,mode,connected_by,created_at,updated_at) "
                + "values (?,?,?,'public','CLOUD',7,now(),now())", id, ref, "https://" + ref + ".supabase.co");
    }

    private SupabaseMigrationManifest manifest() {
        return SupabaseMigrationManifest.create(project, target(), ledger().connectionEpoch(project),
                List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 1;")));
    }

    private String target() { return "supabase-cloud:" + ref; }
    private TestLedger ledger() { return new TestLedger(); }
    private static final class ProcessLoss extends Error {}

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private final class TestLedger extends SupabaseMigrationLedger {
        Consumer<String> cut = phase -> {};
        TestLedger() {
            super(SupabaseOwnerAttestationIT.this.jdbc, SupabaseOwnerAttestationIT.this.transactions, new DataCipher("synthetic-ledger-key-0123456789"));
        }
        @Override protected Instant now() { return time.get(); }
        @Override protected void checkpoint(String phase) { cut.accept(phase); }
    }
}
