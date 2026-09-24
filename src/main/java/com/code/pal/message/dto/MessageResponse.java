package com.code.pal.message.dto;

import com.code.pal.message.domain.Message;
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
        Instant createdAt,
        Instant updatedAt
) {

    public static MessageResponse from(Message m) {
        return new MessageResponse(
                m.getId(), m.getProjectId(), m.getRole(), m.getContent(), m.getStatus(),
                m.getMode(), m.getReplyToId(), m.getCreatedAt(), m.getUpdatedAt());
    }
}
