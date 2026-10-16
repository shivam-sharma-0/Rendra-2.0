package com.code.pal.project.supabase;

import com.code.pal.shared.config.DataCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Local proof only. A lease cannot cancel a remote request, so uncertain sends stay quarantined. */
@Slf4j
@Service
public class SupabaseMigrationLedger {
    private static final String ACTIVE = "('PREPARED','APPLYING','RECOVERY_REQUIRED')";
    private static final long LEASE_SECONDS = 120;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DataCipher cipher;

    public SupabaseMigrationLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions, DataCipher cipher) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.cipher = cipher;
    }

    public record Outcome(UUID operationId, String state, List<String> applied, String failed, String code, List<String> remaining) {}

    private record Operation(UUID id, UUID project, String target, UUID epoch, String hash, Long actor, String state,
                             UUID owner, long fence, Instant expires, String code) {}

    private record Step(UUID id, String name, String checksum, int bytes, String state) {}
    private record Claim(Operation operation, boolean acquired) {}

    private static final RowMapper<Operation> OPERATIONS = (rs, row) -> new Operation(
            rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("target_key"),
            rs.getObject("connection_epoch", UUID.class), rs.getString("manifest_hash"), rs.getLong("approved_by"), rs.getString("state"),
            rs.getObject("owner_id", UUID.class), rs.getLong("fence"), rs.getTimestamp("lease_expires_at").toInstant(), rs.getString("failure_code"));

    private static final RowMapper<Step> STEPS = (rs, row) -> new Step(rs.getObject("id", UUID.class), rs.getString("name"),
            rs.getString("checksum"), rs.getInt("byte_count"), rs.getString("state"));

    /** Must not be called from a surrounding transaction: every send boundary commits independently. */
    public Outcome execute(Long actor, SupabaseMigrationManifest manifest, String key, Runnable beforeSend,
                           Consumer<SupabaseMigrationManifest.Source> sender) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Apply requires independent transactions");
        if (actor == null || actor <= 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) {
            throw problem("MIGRATION_IDEMPOTENCY_KEY_REQUIRED");
        }
        if (!cipher.available()) throw problem("MIGRATION_SNAPSHOT_ENCRYPTION_UNAVAILABLE");
        checkpoint("BEFORE_CLAIM");
        Claim claim = tx.execute(status -> claim(actor, manifest, key, beforeSend));
        if (!claim.acquired()) return outcome(claim.operation().id());
        Operation owner = claim.operation();
        checkpoint("AFTER_CLAIM");
        var heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "supabase-apply-lease");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(() -> renew(owner), 15, 15, TimeUnit.SECONDS);
        try {
            for (Step step : steps(owner.id())) {
                if (!step.state().equals("PREPARED")) continue;
                String encrypted = jdbc.queryForObject("select sql_cipher from supabase_migration_attempts where id=? and operation_id=?",
                        String.class, step.id(), owner.id());
                var source = new SupabaseMigrationManifest.Source(step.name(), step.checksum(), step.bytes(), cipher.decrypt(encrypted));
                boolean dispatched = Boolean.TRUE.equals(tx.execute(status -> dispatch(owner, step, manifest, beforeSend)));
                if (!dispatched) break;
                checkpoint("AFTER_SENDING");
                sender.accept(source);
                checkpoint("AFTER_REMOTE_SUCCESS");
                boolean settled = Boolean.TRUE.equals(tx.execute(status -> settle(owner, step, manifest)));
                if (!settled) break;
                checkpoint("AFTER_SETTLE");
            }
            tx.executeWithoutResult(status -> complete(owner));
        } catch (RuntimeException failure) {
            // Never return provider text, credentials, SQL, or an invented rollback guarantee.
            tx.executeWithoutResult(status -> stop(owner));
            log.warn("supabase_apply_stopped operationId={} code=MIGRATION_DISPATCH_STOPPED", owner.id());
        } finally {
            heartbeat.shutdownNow();
        }
        return outcome(owner.id());
    }

    private Claim claim(Long actor, SupabaseMigrationManifest manifest, String key, Runnable beforeSend) {
        lockProject(manifest.projectId());
        requireConnection(manifest);
        lock("supabase-target:" + manifest.targetKey());
        beforeSend.run();
        List<Operation> existing = jdbc.query("select * from supabase_apply_operations where project_id=? and approved_by=? and idempotency_key=? for update",
                OPERATIONS, manifest.projectId(), actor, key);
        if (!existing.isEmpty()) {
            Operation operation = existing.getFirst();
            if (!operation.hash().equals(manifest.hash()) || !operation.target().equals(manifest.targetKey()) || !operation.epoch().equals(manifest.connectionEpoch())) {
                throw problem("MIGRATION_IDEMPOTENCY_CONFLICT");
            }
            if (!runnable(operation) || operation.expires().isAfter(now())) return new Claim(operation, false);
            if (uncertain(operation.id())) {
                quarantine(operation);
                return new Claim(operation(operation.id(), false), false);
            }
            UUID successor = UUID.randomUUID();
            jdbc.update("update supabase_apply_operations set owner_id=?,fence=fence+1,lease_expires_at=?,next_reconcile_at=null,updated_at=? "
                            + "where id=? and owner_id=? and fence=?", successor, at(now().plusSeconds(LEASE_SECONDS)), at(now()),
                    operation.id(), operation.owner(), operation.fence());
            return new Claim(operation(operation.id(), false), true);
        }
        Integer active = jdbc.queryForObject("select count(*) from supabase_apply_operations where (project_id=? or target_key=?) and state in " + ACTIVE,
                Integer.class, manifest.projectId(), manifest.targetKey());
        if (active != null && active > 0) throw problem("MIGRATION_TARGET_BUSY");
        List<SupabaseMigrationManifest.Source> pending = pending(manifest);
        UUID id = UUID.randomUUID();
        Instant timestamp = now();
        jdbc.update("insert into supabase_apply_operations(id,project_id,target_key,connection_epoch,manifest_hash,idempotency_key,approved_by,state,"
                        + "owner_id,fence,lease_expires_at,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,1,?,?,?)",
                id, manifest.projectId(), manifest.targetKey(), manifest.connectionEpoch(), manifest.hash(), key, actor,
                pending.isEmpty() ? "COMPLETED" : "PREPARED", UUID.randomUUID(), at(timestamp.plusSeconds(LEASE_SECONDS)), at(timestamp), at(timestamp));
        for (int i = 0; i < pending.size(); i++) {
            var source = pending.get(i);
            jdbc.update("insert into supabase_migration_attempts(id,operation_id,ordinal,name,checksum,byte_count,sql_cipher,state) "
                            + "values (?,?,?,?,?,?,?,'PREPARED')", UUID.randomUUID(), id, i, source.name(), source.checksum(), source.byteCount(), cipher.encrypt(source.sql()));
        }
        return new Claim(operation(id, false), !pending.isEmpty());
    }

    /** Historical target/file proof is independent of credential epochs and Rendra project aliases. */
    private List<SupabaseMigrationManifest.Source> pending(SupabaseMigrationManifest manifest) {
        Map<String, String> proof = new HashMap<>();
        jdbc.query("select a.name,a.checksum from supabase_migration_attempts a join supabase_apply_operations o on o.id=a.operation_id "
                + "where o.target_key=? and a.state='APPLIED'", rs -> {
            String previous = proof.put(rs.getString("name"), rs.getString("checksum"));
            if (previous != null && !previous.equals(rs.getString("checksum"))) throw problem("MIGRATION_APPLIED_SOURCE_CHANGED");
        }, manifest.targetKey());
        Map<String, String> current = new HashMap<>();
        manifest.sources().forEach(source -> current.put(source.name(), source.checksum()));
        proof.forEach((name, checksum) -> {
            if (!checksum.equals(current.get(name))) throw problem("MIGRATION_APPLIED_SOURCE_CHANGED");
        });
        requireBoundLegacyHistory(manifest.projectId(), manifest.targetKey(), proof);
        return manifest.sources().stream().filter(source -> !proof.containsKey(source.name())).toList();
    }

    private void requireBoundLegacyHistory(UUID project, String target, Map<String, String> proof) {
        // Include aliases of this target. A second Rendra project must not bypass known legacy uncertainty.
        jdbc.query("select m.name,m.checksum,m.project_id,c.mode,c.project_ref,c.url from project_supabase_migrations m "
                + "left join project_supabase_connections c on c.project_id=m.project_id "
                + "where m.project_id=? or (c.mode='CLOUD' and 'supabase-cloud:' || c.project_ref=?) or c.mode='SELF_HOSTED'", rs -> {
            boolean sameProject = project.equals(rs.getObject("project_id", UUID.class));
            String mode = rs.getString("mode");
            boolean sameTarget = mode != null && target.equals(targetKey(mode, rs.getString("project_ref"), rs.getString("url")));
            if ((sameProject || sameTarget) && !rs.getString("checksum").equals(proof.get(rs.getString("name")))) throw problem("MIGRATION_LEGACY_UNBOUND");
        }, project, target);
    }

    private boolean dispatch(Operation owner, Step step, SupabaseMigrationManifest manifest, Runnable beforeSend) {
        // The existing connection row serializes dispatch with changes. Admission's try-lock is unnecessary here.
        requireConnection(manifest);
        Operation current = operation(owner.id(), true);
        if (!ownsLive(owner, current)) return false;
        beforeSend.run();
        List<Step> all = steps(owner.id());
        for (Step earlier : all) {
            if (earlier.id().equals(step.id())) break;
            if (!earlier.state().equals("APPLIED")) throw problem("MIGRATION_PREDECESSOR_UNPROVEN");
        }
        int changed = jdbc.update("update supabase_migration_attempts set state='SENDING',dispatch_fence=?,sent_at=?,updated_at=? "
                + "where id=? and operation_id=? and state='PREPARED'", owner.fence(), at(now()), at(now()), step.id(), owner.id());
        if (changed != 1) return false;
        updateState(owner, "APPLYING", null, null);
        return true;
    }

    private boolean settle(Operation owner, Step step, SupabaseMigrationManifest manifest) {
        requireConnection(manifest);
        Operation current = operation(owner.id(), true);
        if (!ownsLive(owner, current)) {
            if (owns(owner, current) && runnable(current)) quarantine(current);
            return false;
        }
        int changed = jdbc.update("update supabase_migration_attempts set state='APPLIED',settled_at=?,updated_at=? "
                        + "where id=? and operation_id=? and state='SENDING' and dispatch_fence=?",
                at(now()), at(now()), step.id(), owner.id(), owner.fence());
        if (changed != 1) return false;
        jdbc.update("insert into project_supabase_migrations(id,project_id,name,checksum,applied_by,applied_at) values (?,?,?,?,?,?) "
                        + "on conflict (project_id,name) do update set checksum=excluded.checksum,applied_by=excluded.applied_by,applied_at=excluded.applied_at",
                UUID.randomUUID(), owner.project(), step.name(), step.checksum(), owner.actor(), at(now()));
        return true;
    }

    private void complete(Operation owner) {
        Operation current = operation(owner.id(), true);
        if (!ownsLive(owner, current)) return;
        if (steps(owner.id()).stream().allMatch(step -> step.state().equals("APPLIED"))) updateState(owner, "COMPLETED", null, null);
    }

    private void stop(Operation owner) {
        Operation current = operation(owner.id(), true);
        if (!owns(owner, current) || !runnable(current)) return;
        if (uncertain(owner.id())) {
            quarantine(current);
        } else {
            jdbc.update("update supabase_migration_attempts set state='NOT_SENT',failure_code='MIGRATION_DISPATCH_STOPPED',updated_at=? "
                    + "where operation_id=? and state='PREPARED'", at(now()), owner.id());
            updateState(owner, "ABORTED", "MIGRATION_DISPATCH_STOPPED", null);
        }
    }

    private void quarantine(Operation owner) {
        jdbc.update("update supabase_migration_attempts set state='UNKNOWN',failure_code='MIGRATION_OUTCOME_UNKNOWN',updated_at=? "
                + "where operation_id=? and state='SENDING'", at(now()), owner.id());
        jdbc.update("update supabase_apply_operations set state='RECOVERY_REQUIRED',failure_code='MIGRATION_OUTCOME_UNKNOWN',"
                        + "fence=fence+1,next_reconcile_at=?,updated_at=? where id=? and owner_id=? and fence=? and state in " + ACTIVE,
                at(now().plusSeconds(300)), at(now()), owner.id(), owner.owner(), owner.fence());
    }

    private void updateState(Operation owner, String state, String code, Instant reconcile) {
        jdbc.update("update supabase_apply_operations set state=?,failure_code=?,next_reconcile_at=?,updated_at=? where id=? and owner_id=? and fence=?",
                state, code, reconcile == null ? null : at(reconcile), at(now()), owner.id(), owner.owner(), owner.fence());
    }

    private void renew(Operation owner) {
        try {
            Instant timestamp = now();
            jdbc.update("update supabase_apply_operations set lease_expires_at=?,updated_at=? where id=? and owner_id=? and fence=? "
                            + "and lease_expires_at>? and state in ('PREPARED','APPLYING')",
                    at(timestamp.plusSeconds(LEASE_SECONDS)), at(timestamp), owner.id(), owner.owner(), owner.fence(), at(timestamp));
        } catch (RuntimeException failure) {
            log.warn("supabase_apply_renewal_failed operationId={}", owner.id());
        }
    }

    /** Metadata reconciliation only; never sends SQL or claims that an absent remote receipt means rollback. */
    public int recover(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid recovery limit");
        return tx.execute(status -> {
            Instant timestamp = now();
            List<Operation> due = jdbc.query("select * from supabase_apply_operations where state in " + ACTIVE
                            + " and lease_expires_at<=? and (next_reconcile_at is null or next_reconcile_at<=?) "
                            + "order by created_at,id limit ? for update skip locked", OPERATIONS, at(timestamp), at(timestamp), limit);
            for (Operation operation : due) {
                if (uncertain(operation.id()) || operation.state().equals("RECOVERY_REQUIRED")) {
                    quarantine(operation);
                } else if (steps(operation.id()).stream().allMatch(step -> step.state().equals("APPLIED"))) {
                    updateState(operation, "COMPLETED", null, null);
                } else {
                    // Only an authenticated, still-approved retry may resume proved-unsent work.
                    jdbc.update("update supabase_apply_operations set owner_id=?,fence=fence+1,next_reconcile_at=?,updated_at=? "
                                    + "where id=? and owner_id=? and fence=?", UUID.randomUUID(), at(timestamp.plusSeconds(300)), at(timestamp),
                            operation.id(), operation.owner(), operation.fence());
                }
            }
            return due.size();
        });
    }

    @Scheduled(fixedDelay = 30_000)
    public void reconcileExpired() {
        try {
            recover(20);
        } catch (RuntimeException failure) {
            log.warn("supabase_apply_recovery_failed");
        }
    }

    public UUID connectionEpoch(UUID project) {
        List<UUID> epochs = jdbc.query("select connection_epoch from project_supabase_connections where project_id=?",
                (rs, row) -> rs.getObject(1, UUID.class), project);
        if (epochs.isEmpty()) throw problem("MIGRATION_CONNECTION_CHANGED");
        return epochs.getFirst();
    }

    /** Call before touching the connection row, inside the connect/disconnect transaction. */
    public void guardConnectionChange(UUID project) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Connection guard requires a transaction");
        lockProject(project);
        List<String> targets = jdbc.query("select mode,project_ref,url from project_supabase_connections where project_id=? for update",
                (rs, row) -> targetKey(rs.getString("mode"), rs.getString("project_ref"), rs.getString("url")), project);
        Integer active = jdbc.queryForObject("select count(*) from supabase_apply_operations where project_id=? and state in " + ACTIVE, Integer.class, project);
        if (active != null && active > 0) throw problem("MIGRATION_TARGET_BUSY");
        Integer unbound = jdbc.queryForObject("select count(*) from project_supabase_migrations m where m.project_id=? and not exists "
                        + "(select 1 from supabase_migration_attempts a join supabase_apply_operations o on o.id=a.operation_id "
                        + "where o.target_key=? and a.name=m.name and a.checksum=m.checksum and a.state='APPLIED')",
                Integer.class, project, targets.isEmpty() ? "disconnected" : targets.getFirst());
        if (unbound != null && unbound > 0) throw problem("MIGRATION_LEGACY_UNBOUND");
    }

    /** Call after flushing a connection insert/update in that same guarded transaction; a delete leaves no epoch to bump. */
    public void connectionChanged(UUID project) {
        guardConnectionChange(project);
        jdbc.update("update project_supabase_connections set connection_epoch=? where project_id=?", UUID.randomUUID(), project);
    }

    public Outcome outcome(Long actor, UUID project, UUID id) {
        Integer count = jdbc.queryForObject("select count(*) from supabase_apply_operations where id=? and project_id=? and approved_by=?",
                Integer.class, id, project, actor);
        if (count == null || count != 1) throw problem("MIGRATION_OPERATION_NOT_FOUND");
        return outcome(id);
    }

    public Outcome outcomeByKey(Long actor, UUID project, String key) {
        List<UUID> ids = jdbc.query("select id from supabase_apply_operations where project_id=? and approved_by=? and idempotency_key=?",
                (rs, row) -> rs.getObject(1, UUID.class), project, actor, key);
        if (ids.size() != 1) throw problem("MIGRATION_OPERATION_NOT_FOUND");
        return outcome(ids.getFirst());
    }

    private Outcome outcome(UUID id) {
        return tx.execute(status -> {
            Operation operation = operation(id, true);
            var applied = new ArrayList<String>();
            var remaining = new ArrayList<String>();
            String failed = null;
            for (Step step : steps(id)) {
                if (step.state().equals("APPLIED")) applied.add(step.name());
                else if (failed == null && (step.state().equals("UNKNOWN") || step.state().equals("SENDING") || step.state().equals("NOT_SENT"))) failed = step.name();
                else remaining.add(step.name());
            }
            return new Outcome(id, operation.state(), List.copyOf(applied), failed, operation.code(), List.copyOf(remaining));
        });
    }

    private void requireConnection(SupabaseMigrationManifest manifest) {
        List<String> actual = jdbc.query("select mode,project_ref,url,connection_epoch from project_supabase_connections where project_id=? for update",
                (rs, row) -> targetKey(rs.getString("mode"), rs.getString("project_ref"), rs.getString("url")) + "|" + rs.getObject("connection_epoch"), manifest.projectId());
        if (actual.size() != 1 || !actual.getFirst().equals(manifest.targetKey() + "|" + manifest.connectionEpoch())) {
            throw problem("MIGRATION_CONNECTION_CHANGED");
        }
    }

    static String targetKey(String mode, String ref, String url) {
        if ("CLOUD".equals(mode) && ref != null && ref.matches("[a-z0-9]{20}")) return "supabase-cloud:" + ref;
        if ("SELF_HOSTED".equals(mode) && url != null) {
            return "supabase-operator:" + SupabaseService.checksum(url.trim().replaceAll("/+$", "").toLowerCase(Locale.ROOT));
        }
        throw problem("MIGRATION_CONNECTION_CHANGED");
    }

    private void lockProject(UUID project) { lock("supabase-project:" + project); }

    private void lock(String key) {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class, key);
        if (!Boolean.TRUE.equals(locked)) throw problem("MIGRATION_TARGET_BUSY");
    }

    private Operation operation(UUID id, boolean lock) {
        return jdbc.queryForObject("select * from supabase_apply_operations where id=?" + (lock ? " for update" : ""), OPERATIONS, id);
    }

    private List<Step> steps(UUID id) {
        return jdbc.query("select id,name,checksum,byte_count,state from supabase_migration_attempts where operation_id=? order by ordinal,attempt_number", STEPS, id);
    }

    private boolean uncertain(UUID id) {
        return steps(id).stream().anyMatch(step -> step.state().equals("SENDING") || step.state().equals("UNKNOWN"));
    }

    private static boolean runnable(Operation operation) { return operation.state().equals("PREPARED") || operation.state().equals("APPLYING"); }
    private static boolean owns(Operation owner, Operation current) { return owner.owner().equals(current.owner()) && owner.fence() == current.fence(); }
    private boolean ownsLive(Operation owner, Operation current) { return owns(owner, current) && runnable(current) && current.expires().isAfter(now()); }
    private static Timestamp at(Instant timestamp) { return Timestamp.from(timestamp); }
    private static SupabaseMigrationException problem(String code) { return new SupabaseMigrationException(code); }

    protected Instant now() { return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant(); }

    /** Deterministic crash-injection seam; production has no callback or extra side effect. */
    protected void checkpoint(String phase) {}
}
