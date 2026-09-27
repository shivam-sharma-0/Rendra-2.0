package com.code.pal.agent.change;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class FileVersions {
    public static final String ABSENT = "absent";
    private FileVersions() {}

    public static String of(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return ABSENT;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "non-file";
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint project file", e);
        }
    }
}
