package com.code.pal.message.dto;

import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageFeedback;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.domain.MessageStatus;
import com.code.pal.project.domain.ProjectType;

import java.time.Instant;
import java.util.UUID;

/** Public view of a message. */
public record MessageResponse(
        String id,
        UUID projectId,
        MessageRole role,
        String content,
        MessageStatus status,
        ProjectType mode,
        String replyToId,
        MessageFeedback feedback,
        long seq,
        Instant createdAt,
        Instant updatedAt,
        java.util.List<String> attachments
) {

    public static MessageResponse from(Message m) {
        return new MessageResponse(
                m.getId(), m.getProjectId(), m.getRole(), m.getContent(), m.getStatus(),
                m.getMode(), m.getReplyToId(), m.getFeedback(), m.getSeq(), m.getCreatedAt(), m.getUpdatedAt(),
                m.attachmentPaths());
    }
}
