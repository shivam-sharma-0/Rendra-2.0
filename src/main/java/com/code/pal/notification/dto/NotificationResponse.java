package com.code.pal.notification.dto;

import com.code.pal.notification.domain.Notification;
import com.code.pal.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        String projectId,
        boolean read,
        Instant createdAt
) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getBody(),
                n.getProjectId() != null ? n.getProjectId().toString() : null,
                n.getReadAt() != null,
                n.getCreatedAt());
    }
}
