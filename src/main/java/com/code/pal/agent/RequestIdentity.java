package com.code.pal.agent;

import com.code.pal.message.domain.Message;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Detects modification of an accepted request without storing another copy of private text. */
final class RequestIdentity {
    static String fingerprint(Message message) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            List<Object> fields = new ArrayList<>(Arrays.asList(message.getProjectId(), message.getId(), message.getAuthorId(),
                    message.getMode(), message.getContent(), message.getAttachments()));
            // Keep legacy fingerprints byte-for-byte stable when there is no proposal binding.
            if (message.getSourceProposalId() != null || message.getResolvedRequestText() != null
                    || message.getResolvedAttachments() != null) {
                fields.add(message.getSourceProposalId());
                fields.add(message.getResolvedRequestText());
                fields.add(message.getResolvedAttachments());
            }
            for (Object field : fields) {
                String value = field == null ? "" : field.toString();
                digest.update((value.length() + ":" + value).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
