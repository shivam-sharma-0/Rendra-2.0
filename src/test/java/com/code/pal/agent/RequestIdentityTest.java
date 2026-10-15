package com.code.pal.agent;

import com.code.pal.message.domain.Message;
import com.code.pal.project.domain.ProjectType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequestIdentityTest {
    private Message legacy() {
        return Message.user(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "u_legacy", "add dark mode", ProjectType.BUILD, 7L);
    }

    @Test
    void preMigrationAcceptedRequestsKeepTheirExactFingerprint() {
        assertEquals("3c12d1a6b05fca114372002c1cf2cc25db73f7803d8b583dd8ffb2b019cc09e3",
                RequestIdentity.fingerprint(legacy()));
    }

    @Test
    void boundGoalAndAttachmentChangesInvalidateTheAcceptedFingerprint() {
        Message request = legacy();
        request.setSourceProposalId("a_proposal");
        request.setResolvedRequestText("Build the proposed changes");
        request.setResolvedAttachments("uploads/reference.png");
        String accepted = RequestIdentity.fingerprint(request);
        request.setResolvedAttachments("uploads/other.png");
        assertNotEquals(accepted, RequestIdentity.fingerprint(request));
        request.setResolvedAttachments("uploads/reference.png");
        request.setResolvedRequestText("Different work");
        assertNotEquals(accepted, RequestIdentity.fingerprint(request));
    }

    @Test
    void injectingResolvedTextWithoutAProposalIdStillChangesIdentity() {
        Message request = legacy();
        String accepted = RequestIdentity.fingerprint(request);
        request.setResolvedRequestText("Injected instructions");
        assertNotEquals(accepted, RequestIdentity.fingerprint(request));
    }
}
