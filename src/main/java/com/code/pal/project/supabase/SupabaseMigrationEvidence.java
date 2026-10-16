package com.code.pal.project.supabase;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Execution receipts and owner declarations deliberately retain separate provenance. */
final class SupabaseMigrationEvidence {
    private SupabaseMigrationEvidence() {}

    record Applied(String checksum, UUID attestationId) {}

    static Map<String, Applied> applied(JdbcTemplate jdbc, String target) {
        Map<String, Applied> result = new LinkedHashMap<>();
        jdbc.query("select a.name,a.checksum from supabase_migration_attempts a join supabase_apply_operations o on o.id=a.operation_id "
                + "where o.target_key=? and a.state='APPLIED' order by a.name,a.id", rs -> {
            put(result, rs.getString("name"), new Applied(rs.getString("checksum"), null));
        }, target);
        jdbc.query("select f.name,f.checksum,r.id from supabase_owner_attested_files f join supabase_owner_attestations r on r.id=f.attestation_id "
                + "where r.target_key=? and f.decision='APPLIED' order by r.attested_at,r.id,f.ordinal", rs -> {
            put(result, rs.getString("name"), new Applied(rs.getString("checksum"), rs.getObject("id", UUID.class)));
        }, target);
        return result;
    }

    private static void put(Map<String, Applied> result, String name, Applied value) {
        Applied previous = result.putIfAbsent(name, value);
        if (previous != null && !previous.checksum().equals(value.checksum())) {
            throw new SupabaseMigrationException("MIGRATION_APPLIED_SOURCE_CHANGED");
        }
    }

}
