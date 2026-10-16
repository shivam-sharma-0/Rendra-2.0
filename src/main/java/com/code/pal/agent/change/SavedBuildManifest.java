package com.code.pal.agent.change;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Complete regular source inventory; credentials, runtime data and build outputs are not code versions. */
public record SavedBuildManifest(List<File> files) {
    public static final int MAX_FILE_BYTES = 10_000_000;
    private static final int MAX_FILES = 10_000;
    private static final long MAX_BYTES = 64_000_000;
    private static final int MAX_DEPTH = 64;
    private static final Set<String> EXCLUDED = Set.of(
            ".git", ".codepal", ".secrets", ".ssh", "node_modules", "dist", "build", ".cache", ".vite", "coverage");

    public record File(String path, String version) {}

    public SavedBuildManifest {
        if (files == null || files.size() > MAX_FILES) {
            throw new IllegalArgumentException("Invalid saved source inventory");
        }
        var seen = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (File file : files) {
            if (file == null || !source(file.path()) || !ProjectPaths.agentPath(file.path(), false).equals(file.path())
                    || !seen.add(file.path()) || file.version() == null || !file.version().matches("sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid saved source file proof");
            }
        }
        files = files.stream().sorted(Comparator.comparing(File::path)).toList();
    }

    public static SavedBuildManifest scan(ProjectFiles files, UUID project, String root) {
        var result = new ArrayList<File>();
        long bytes = 0;
        for (var entry : files.list(project, root, MAX_DEPTH, EXCLUDED)) {
            if (!source(entry.path())) {
                continue;
            }
            if (entry.directory()) {
                if (entry.path().split("/").length >= MAX_DEPTH) {
                    throw new IllegalStateException("Source exceeds scan depth; no partial restore point saved");
                }
                continue;
            }
            bytes += entry.size();
            if (entry.size() > MAX_FILE_BYTES || bytes > MAX_BYTES || result.size() >= MAX_FILES) {
                throw new IllegalStateException("Source exceeds snapshot budget; no partial restore point saved");
            }
            result.add(new File(entry.path(), files.version(project, root, entry.path())));
        }
        return new SavedBuildManifest(result);
    }

    public String digest() {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (File file : files) {
                hash.update(file.path().getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
                hash.update(file.version().getBytes(StandardCharsets.UTF_8));
                hash.update((byte) 0);
            }
            return "sha256:" + HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean source(String path) {
        if (path == null) {
            return false;
        }
        for (String part : path.toLowerCase(Locale.ROOT).split("/")) {
            if (EXCLUDED.contains(part) || (part.startsWith(".env") && !part.equals(".env.example"))
                    || part.endsWith(".pem") || part.endsWith(".key") || part.endsWith(".p12") || part.equals("id_rsa")) {
                return false;
            }
        }
        return true;
    }
}
