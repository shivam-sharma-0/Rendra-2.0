package com.code.pal.project.supabase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Metadata-only owner exit; no remote executor, credential or mutable SQL body enters this component. */
public final class SupabaseOwnerAttestations {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Supplier<Instant> now;
    private final Consumer<String> checkpoint;

    SupabaseOwnerAttestations(JdbcTemplate jdbc, TransactionTemplate tx, Supplier<Instant> now, Consumer<String> checkpoint) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.now = now;
        this.checkpoint = checkpoint;
    }

    public record FileDigest(String name, String checksum) {}
    public record LegacyReview(UUID projectId, String targetKey, UUID connectionEpoch, String hash, List<FileDigest> files) {
        public LegacyReview { files = List.copyOf(files); }
    }
    public record UnknownStep(UUID attemptId, String name, String checksum) {}
    public record RecoveryReview(UUID operationId, String targetKey, UUID connectionEpoch, String state, long fence,
                                 Instant leaseExpiresAt, String hash, List<UnknownStep> unknown, Receipt resolution) {
        public RecoveryReview { unknown = List.copyOf(unknown); }
    }
    public record ResolutionDecision(UUID attemptId, String decision) {}
    public record AttestedFile(String name, String checksum, String decision, UUID attemptId) {}
    public record Receipt(UUID id, String kind, UUID operationId, String targetKey, Long attestedBy, Instant attestedAt,
                          String evidence, List<AttestedFile> files) {
        public Receipt { files = List.copyOf(files); }
    }

    private record Binding(String target, UUID epoch) {}
    private record Operation(UUID id, String target, String state, long fence, Instant expires) {}

    LegacyReview legacyReview(Long actor, UUID project, Runnable authorize) {
        return tx.execute(status -> {
            authorize.run();
            Binding binding = lockConnection(project, true);
            lockTarget(binding.target());
            return legacy(project, binding);
        });
    }

    Receipt adoptLegacy(Long actor, UUID project, String reviewHash, String key, boolean confirmed, Runnable authorize) {
        requireInput(actor, reviewHash, key, confirmed);
        String requestHash = digest("legacy-request-v1", reviewHash);
        Receipt result = tx.execute(status -> {
            authorize.run();
            Receipt previous = repeated(actor, project, key, requestHash);
            if (previous != null) return previous;
            Binding binding = lockConnection(project, true);
            lockTarget(binding.target());
            Integer active = jdbc.queryForObject("select count(*) from supabase_apply_operations where (project_id=? or target_key=?) "
                    + "and state in ('PREPARED','APPLYING','RECOVERY_REQUIRED')", Integer.class, project, binding.target());
            if (active != null && active > 0) throw problem("MIGRATION_TARGET_BUSY");
            LegacyReview review = legacy(project, binding);
            requireReview(reviewHash, review.hash());
            if (review.files().isEmpty()) throw problem("MIGRATION_NO_LEGACY_FILES");
            List<AttestedFile> files = review.files().stream().map(file -> new AttestedFile(file.name(), file.checksum(), "APPLIED", null)).toList();
            Receipt receipt = insert(actor, project, binding, null, null, "LEGACY", reviewHash, requestHash, key, files);
            checkpoint.accept("AFTER_OWNER_ATTESTATION");
            rotateTargetEpochs(binding.target(), project);
            return receipt;
        });
        checkpoint.accept("AFTER_OWNER_ATTESTATION_COMMIT");
        return result;
    }

    RecoveryReview recoveryReview(Long actor, UUID project, UUID operation, Runnable authorize) {
        return tx.execute(status -> {
            authorize.run();
            Binding binding = lockConnection(project, false);
            Operation current = operation(project, operation, false);
            lockTarget(current.target());
            current = operation(project, operation, true);
            return recovery(current, binding);
        });
    }

    Receipt resolveRecovery(Long actor, UUID project, UUID operation, String reviewHash, String key,
                            List<ResolutionDecision> decisions, boolean senderQuiescent, Runnable authorize) {
        requireInput(actor, reviewHash, key, senderQuiescent);
        if (operation == null) throw problem("MIGRATION_OPERATION_NOT_FOUND");
        List<ResolutionDecision> choices = validateChoices(decisions);
        var requestParts = new ArrayList<String>(List.of("resolution-request-v1", operation.toString(), reviewHash));
        choices.forEach(choice -> {
            requestParts.add(choice.attemptId().toString());
            requestParts.add(choice.decision());
        });
        String requestHash = digest(requestParts.toArray(String[]::new));
        Receipt result = tx.execute(status -> {
            authorize.run();
            Receipt previous = repeated(actor, project, key, requestHash);
            if (previous != null) return previous;
            Binding binding = lockConnection(project, false);
            Operation current = operation(project, operation, false);
            lockTarget(current.target());
            current = operation(project, operation, true);
            RecoveryReview review = recovery(current, binding);
            requireReview(reviewHash, review.hash());
            if (!current.state().equals("RECOVERY_REQUIRED")) throw problem("MIGRATION_NOT_RECOVERABLE");
            if (current.expires().isAfter(now.get())) throw problem("MIGRATION_SENDER_MAY_BE_ACTIVE");
            Map<UUID, String> byAttempt = new HashMap<>();
            choices.forEach(choice -> byAttempt.put(choice.attemptId(), choice.decision()));
            if (review.unknown().isEmpty() || review.unknown().size() != byAttempt.size()) throw problem("MIGRATION_RESOLUTION_INCOMPLETE");
            List<AttestedFile> files = review.unknown().stream().map(step -> {
                String decision = byAttempt.get(step.attemptId());
                if (decision == null) throw problem("MIGRATION_RESOLUTION_INCOMPLETE");
                return new AttestedFile(step.name(), step.checksum(), decision, step.attemptId());
            }).toList();
            Binding auditBinding = new Binding(current.target(), binding == null ? null : binding.epoch());
            Receipt receipt = insert(actor, project, auditBinding, current.id(), current.fence(), "RESOLUTION", reviewHash, requestHash, key, files);
            checkpoint.accept("AFTER_OWNER_ATTESTATION");
            Instant timestamp = now.get();
            int changed = jdbc.update("update supabase_apply_operations set state='RESOLVED_ATTESTED',owner_id=?,fence=fence+1,"
                            + "failure_code='MIGRATION_OWNER_ATTESTED',lease_expires_at=?,next_reconcile_at=null,updated_at=? "
                            + "where id=? and project_id=? and fence=? and state='RECOVERY_REQUIRED'",
                    UUID.randomUUID(), at(timestamp), at(timestamp), current.id(), project, current.fence());
            if (changed != 1) throw problem("MIGRATION_OWNER_REVIEW_CHANGED");
            rotateTargetEpochs(current.target(), project);
            return receipt;
        });
        checkpoint.accept("AFTER_OWNER_ATTESTATION_COMMIT");
        return result;
    }

    private LegacyReview legacy(UUID project, Binding binding) {
        var proofs = SupabaseMigrationEvidence.applied(jdbc, binding.target());
        List<FileDigest> all = jdbc.query("select name,checksum from project_supabase_migrations where project_id=? order by name limit 1001",
                (rs, row) -> new FileDigest(rs.getString("name"), rs.getString("checksum")), project);
        if (all.size() > 1000) throw problem("MIGRATION_LEGACY_REVIEW_TOO_LARGE");
        List<FileDigest> files = all.stream().filter(file -> {
            var proof = proofs.get(file.name());
            if (proof != null && !proof.checksum().equals(file.checksum())) throw problem("MIGRATION_APPLIED_SOURCE_CHANGED");
            return proof == null;
        }).toList();
        var parts = new ArrayList<String>(List.of("legacy-review-v1", project.toString(), binding.target(), binding.epoch().toString()));
        files.forEach(file -> {
            parts.add(file.name());
            parts.add(file.checksum());
        });
        return new LegacyReview(project, binding.target(), binding.epoch(), digest(parts.toArray(String[]::new)), files);
    }

    private RecoveryReview recovery(Operation operation, Binding binding) {
        List<UnknownStep> unknown = jdbc.query("select id,name,checksum from supabase_migration_attempts where operation_id=? and state='UNKNOWN' order by ordinal",
                (rs, row) -> new UnknownStep(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("checksum")), operation.id());
        UUID epoch = binding == null ? null : binding.epoch();
        String currentTarget = binding == null ? "disconnected" : binding.target();
        var parts = new ArrayList<String>(List.of("recovery-review-v1", operation.id().toString(), operation.target(), currentTarget,
                String.valueOf(epoch), operation.state(), Long.toString(operation.fence())));
        unknown.forEach(step -> {
            parts.add(step.attemptId().toString());
            parts.add(step.name());
            parts.add(step.checksum());
        });
        List<UUID> receipts = jdbc.query("select id from supabase_owner_attestations where operation_id=?", (rs, row) -> rs.getObject(1, UUID.class), operation.id());
        return new RecoveryReview(operation.id(), operation.target(), epoch, operation.state(), operation.fence(), operation.expires(),
                digest(parts.toArray(String[]::new)), unknown, receipts.isEmpty() ? null : receipt(receipts.getFirst()));
    }

    private Receipt insert(Long actor, UUID project, Binding binding, UUID operation, Long fence, String kind,
                           String reviewHash, String requestHash, String key, List<AttestedFile> files) {
        UUID id = UUID.randomUUID();
        Instant timestamp = now.get();
        jdbc.update("insert into supabase_owner_attestations(id,project_id,target_key,connection_epoch,operation_id,operation_fence,kind,review_hash,"
                        + "request_hash,idempotency_key,attested_by,attested_at,sender_quiescent,file_count) values (?,?,?,?,?,?,?,?,?,?,?,?,true,?)",
                id, project, binding.target(), binding.epoch(), operation, fence, kind, reviewHash, requestHash, key, actor, at(timestamp), files.size());
        for (int i = 0; i < files.size(); i++) {
            AttestedFile file = files.get(i);
            jdbc.update("insert into supabase_owner_attested_files(attestation_id,ordinal,name,checksum,decision,attempt_id) values (?,?,?,?,?,?)",
                    id, i, file.name(), file.checksum(), file.decision(), file.attemptId());
        }
        return new Receipt(id, kind, operation, binding.target(), actor, timestamp, "OWNER_ATTESTED", files);
    }

    private Receipt repeated(Long actor, UUID project, String key, String requestHash) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id,request_hash from supabase_owner_attestations "
                + "where project_id=? and attested_by=? and idempotency_key=?", project, actor, key);
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.getFirst();
        if (!requestHash.equals(row.get("request_hash"))) throw problem("MIGRATION_IDEMPOTENCY_CONFLICT");
        return receipt((UUID) row.get("id"));
    }

    private Receipt receipt(UUID id) {
        List<AttestedFile> files = jdbc.query("select name,checksum,decision,attempt_id from supabase_owner_attested_files where attestation_id=? order by ordinal",
                (rs, row) -> new AttestedFile(rs.getString("name"), rs.getString("checksum"), rs.getString("decision"), rs.getObject("attempt_id", UUID.class)), id);
        return jdbc.queryForObject("select * from supabase_owner_attestations where id=?", (rs, row) -> new Receipt(id, rs.getString("kind"),
                rs.getObject("operation_id", UUID.class), rs.getString("target_key"), rs.getLong("attested_by"), rs.getTimestamp("attested_at").toInstant(),
                "OWNER_ATTESTED", files), id);
    }

    private Binding lockConnection(UUID project, boolean required) {
        lock("supabase-project:" + project);
        List<Binding> values = jdbc.query("select mode,project_ref,url,connection_epoch from project_supabase_connections where project_id=? for update",
                (rs, row) -> new Binding(SupabaseMigrationLedger.targetKey(rs.getString("mode"), rs.getString("project_ref"), rs.getString("url")),
                        rs.getObject("connection_epoch", UUID.class)), project);
        if (values.isEmpty() && required) throw problem("MIGRATION_CONNECTION_CHANGED");
        return values.isEmpty() ? null : values.getFirst();
    }

    private Operation operation(UUID project, UUID id, boolean lock) {
        List<Operation> rows = jdbc.query("select id,target_key,state,fence,lease_expires_at from supabase_apply_operations where id=? and project_id=?"
                        + (lock ? " for update" : ""), (rs, row) -> new Operation(rs.getObject("id", UUID.class), rs.getString("target_key"), rs.getString("state"),
                        rs.getLong("fence"), rs.getTimestamp("lease_expires_at").toInstant()), id, project);
        if (rows.isEmpty()) throw problem("MIGRATION_OPERATION_NOT_FOUND");
        return rows.getFirst();
    }

    private void rotateTargetEpochs(String target, UUID origin) {
        boolean cloud = target.startsWith("supabase-cloud:");
        var ids = jdbc.query("select project_id,mode,project_ref,url from project_supabase_connections "
                + "where project_id=? or (mode='CLOUD' and project_ref=?) or (mode='SELF_HOSTED' and ?) order by project_id", (rs, row) -> {
            String actual = SupabaseMigrationLedger.targetKey(rs.getString("mode"), rs.getString("project_ref"), rs.getString("url"));
            UUID id = rs.getObject("project_id", UUID.class);
            return actual.equals(target) || id.equals(origin) ? id : null;
        }, origin, cloud ? target.substring("supabase-cloud:".length()) : "", !cloud);
        for (UUID project : ids) {
            if (project != null) jdbc.update("update project_supabase_connections set connection_epoch=? where project_id=?", UUID.randomUUID(), project);
        }
    }

    private void lockTarget(String target) { lock("supabase-target:" + target); }

    private void lock(String key) {
        Boolean locked = jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?,0))", Boolean.class, key);
        if (!Boolean.TRUE.equals(locked)) throw problem("MIGRATION_TARGET_BUSY");
    }

    private static void requireInput(Long actor, String reviewHash, String key, boolean confirmed) {
        if (!confirmed) throw problem("MIGRATION_OWNER_CONFIRMATION_REQUIRED");
        if (actor == null || actor <= 0 || key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw problem("MIGRATION_IDEMPOTENCY_KEY_REQUIRED");
        if (reviewHash == null || !reviewHash.matches("sha256:[0-9a-f]{64}")) throw problem("MIGRATION_OWNER_REVIEW_REQUIRED");
    }

    private static List<ResolutionDecision> validateChoices(List<ResolutionDecision> choices) {
        if (choices == null || choices.isEmpty() || choices.size() > 100) throw problem("MIGRATION_RESOLUTION_INCOMPLETE");
        if (choices.stream().anyMatch(choice -> choice == null || choice.attemptId() == null
                || !("APPLIED".equals(choice.decision()) || "NOT_APPLIED".equals(choice.decision())))) throw problem("MIGRATION_RESOLUTION_INVALID");
        if (choices.stream().map(ResolutionDecision::attemptId).distinct().count() != choices.size()) throw problem("MIGRATION_RESOLUTION_INVALID");
        return choices.stream().sorted(Comparator.comparing(choice -> choice.attemptId().toString())).toList();
    }

    private static void requireReview(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw problem("MIGRATION_OWNER_REVIEW_CHANGED");
    }

    private static String digest(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) canonical.append(part.length()).append(':').append(part);
        return SupabaseService.checksum(canonical.toString());
    }

    private static SupabaseMigrationException problem(String code) { return new SupabaseMigrationException(code); }
    private static Timestamp at(Instant time) { return Timestamp.from(time); }
}
