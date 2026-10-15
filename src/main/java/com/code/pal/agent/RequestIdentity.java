package com.code.pal.agent;

import com.code.pal.message.domain.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Detects modification of an accepted request without storing another copy of private text. */
final class RequestIdentity {
    static String fingerprint(Message message) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object field : new Object[]{message.getProjectId(), message.getId(), message.getAuthorId(),
                    message.getMode(), message.getContent(), message.getAttachments()}) {
                String value = field == null ? "" : field.toString();
                digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
