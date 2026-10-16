package com.code.pal.project.supabase;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Exact reviewed bytes; the digest never depends on JSON/map ordering or credentials. */
public final class SupabaseMigrationManifest {
    public static final int MAX_FILES = 100;
    public static final int MAX_FILE_BYTES = 200_000;
    public static final int MAX_TOTAL_BYTES = 2_000_000;
    private final UUID projectId;
    private final String targetKey;
    private final UUID connectionEpoch;
    private final List<Source> sources;
    private final String hash;

    private SupabaseMigrationManifest(UUID projectId, String targetKey, UUID epoch, List<Source> sources) {
        this.projectId = Objects.requireNonNull(projectId);
        this.connectionEpoch = Objects.requireNonNull(epoch);
        if (targetKey == null || !targetKey.matches("supabase-cloud:[a-z0-9]{20}|supabase-operator:sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid migration target");
        }
        this.targetKey = targetKey;
        if (sources == null || sources.size() > MAX_FILES) throw new IllegalArgumentException("Too many migration files");
        this.sources = sources.stream().sorted(Comparator.comparing(Source::name)).toList();
        var names = new HashSet<String>();
        int bytes = 0;
        StringBuilder canonical = new StringBuilder("supabase-manifest-v1\n").append(projectId).append('\n')
                .append(targetKey).append('\n').append(epoch).append('\n');
        for (Source source : this.sources) {
            if (!names.add(source.name())) throw new IllegalArgumentException("Duplicate migration file");
            bytes += source.byteCount();
            canonical.append(source.name()).append('\n').append(source.checksum()).append('\n').append(source.byteCount()).append('\n');
        }
        if (bytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Migration manifest is too large");
        this.hash = SupabaseService.checksum(canonical.toString());
    }

    public static SupabaseMigrationManifest create(UUID projectId, String targetKey, UUID epoch, List<Source> sources) {
        return new SupabaseMigrationManifest(projectId, targetKey, epoch, sources);
    }

    public UUID projectId() { return projectId; }
    public String targetKey() { return targetKey; }
    public UUID connectionEpoch() { return connectionEpoch; }
    public List<Source> sources() { return sources; }
    public String hash() { return hash; }

    public record Source(String name, String checksum, int byteCount, String sql) {
        public Source {
            if (name == null || name.length() > 200 || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.sql") || name.contains("..")) {
                throw new IllegalArgumentException("Invalid migration file name");
            }
            Objects.requireNonNull(sql);
            int size = sql.getBytes(StandardCharsets.UTF_8).length;
            if (size == 0 || size > MAX_FILE_BYTES || size != byteCount || !SupabaseService.checksum(sql).equals(checksum)) {
                throw new IllegalArgumentException("Invalid migration snapshot");
            }
        }

        public static Source of(String name, String sql) {
            Objects.requireNonNull(sql);
            return new Source(name, SupabaseService.checksum(sql), sql.getBytes(StandardCharsets.UTF_8).length, sql);
        }

        @Override
        public String toString() { return "MigrationSource[name=" + name + ", checksum=" + checksum + ", byteCount=" + byteCount + "]"; }
    }
}
