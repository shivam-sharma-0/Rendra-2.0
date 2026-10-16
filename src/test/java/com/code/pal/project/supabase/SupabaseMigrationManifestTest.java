package com.code.pal.project.supabase;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SupabaseMigrationManifestTest {
    private final UUID project = UUID.randomUUID();
    private final UUID epoch = UUID.randomUUID();

    @Test
    void exactBytesTargetEpochAndSortedFilesDefineTheApproval() {
        var one = SupabaseMigrationManifest.Source.of("1_a.sql", "select 'é';\n");
        var two = SupabaseMigrationManifest.Source.of("2_b.sql", "select 2;");
        var manifest = SupabaseMigrationManifest.create(project, "supabase-cloud:abcdefghijklmnopqrst", epoch, List.of(two, one));
        assertEquals(List.of(one, two), manifest.sources());
        assertEquals(manifest.hash(), SupabaseMigrationManifest.create(project, manifest.targetKey(), epoch, List.of(one, two)).hash());
        assertNotEquals(manifest.hash(), SupabaseMigrationManifest.create(project, manifest.targetKey(), UUID.randomUUID(), List.of(one, two)).hash());
        assertNotEquals(manifest.hash(), SupabaseMigrationManifest.create(project, "supabase-cloud:zyxwvutsrqponmlkjihg", epoch, List.of(one, two)).hash());
        assertNotEquals(manifest.hash(), SupabaseMigrationManifest.create(project, manifest.targetKey(), epoch,
                List.of(SupabaseMigrationManifest.Source.of("1_a.sql", "select 'é';"), two)).hash());
        assertFalse(one.toString().contains("select"), "SQL bodies must not leak through record logging");
    }

    @Test
    void unsafeDuplicateAndOversizedFilesAreRejectedWithoutTruncation() {
        assertThrows(IllegalArgumentException.class, () -> SupabaseMigrationManifest.Source.of("../a.sql", "select 1;"));
        assertThrows(IllegalArgumentException.class, () -> SupabaseMigrationManifest.Source.of("a.sql", "é".repeat(100_001)));
        var source = SupabaseMigrationManifest.Source.of("a.sql", "select 1;");
        String target = "supabase-cloud:abcdefghijklmnopqrst";
        assertThrows(IllegalArgumentException.class, () -> SupabaseMigrationManifest.create(project, target, epoch, List.of(source, source)));
        var sources = new ArrayList<SupabaseMigrationManifest.Source>();
        for (int i = 0; i < 101; i++) sources.add(SupabaseMigrationManifest.Source.of(i + ".sql", "select 1;"));
        assertThrows(IllegalArgumentException.class, () -> SupabaseMigrationManifest.create(project, target, epoch, sources));
    }

    @Test
    void aggregateByteLimitIsInclusiveAndCannotDropTheLastFile() {
        var sources = new ArrayList<SupabaseMigrationManifest.Source>();
        for (int i = 0; i < 10; i++) sources.add(SupabaseMigrationManifest.Source.of(i + ".sql", "x".repeat(200_000)));
        String target = "supabase-cloud:abcdefghijklmnopqrst";
        assertEquals(10, SupabaseMigrationManifest.create(project, target, epoch, sources).sources().size());
        sources.add(SupabaseMigrationManifest.Source.of("last.sql", "x"));
        assertThrows(IllegalArgumentException.class, () -> SupabaseMigrationManifest.create(project, target, epoch, sources));
    }

    @Test
    void operatorUrlAliasesShareOneTargetIdentity() {
        assertEquals(SupabaseMigrationLedger.targetKey("SELF_HOSTED", "self-hosted", "https://operator.invalid/"),
                SupabaseMigrationLedger.targetKey("SELF_HOSTED", "self-hosted", "HTTPS://OPERATOR.INVALID"));
    }
}
