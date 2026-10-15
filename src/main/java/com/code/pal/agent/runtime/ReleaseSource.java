package com.code.pal.agent.runtime;

import com.code.pal.agent.workspace.ProjectFiles;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Content identity only; cannot grant permission or prove database schema compatibility. */
public final class ReleaseSource {
    public static final String PROOF = "SOURCE_REVISION";
    private static final Set<String> SKIP = Set.of(".codepal", ".git", "node_modules", "dist", "build");
    private ReleaseSource() {}
    public static String capture(ProjectFiles files, UUID project, String root) {
        var versions = new TreeMap<String, String>();
        var entries = files.list(project, root, 32, SKIP);
        if (entries.size() > 5000 || entries.stream().anyMatch(e -> e.directory() && e.path().split("/").length >= 32))
            throw new IllegalStateException("Release source exceeds proof limits");
        for (var entry : entries) if (!entry.directory()) {
            String version = files.version(project, root, entry.path());
            if (!version.startsWith("sha256:")) throw new IllegalStateException("Release source changed");
            versions.put(entry.path(), version);
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            versions.forEach((path, version) -> digest.update((path + "\0" + version + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static boolean paired(ProjectFiles files, UUID project, String server) {
        try {
            String siteId = UUID.fromString(files.readText(project, ".codepal/site", "CURRENT").trim()).toString();
            String client = files.readText(project, ".codepal/site/" + siteId, PROOF);
            return client.matches("[a-f0-9]{64}") && client.equals(files.readText(project, server, PROOF));
        } catch (RuntimeException absent) { return false; }
    }
}
