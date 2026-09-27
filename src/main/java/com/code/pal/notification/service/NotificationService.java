package com.code.pal.notification.service;

import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.dto.NotificationResponse;

import java.util.List;
import java.util.UUID;

/**
 * Port other modules use to raise a notification for a user, plus the read side for the feed.
 * Raising a notification is best-effort — it never fails the action that triggered it.
 */
public interface NotificationService {

    void notify(Long recipientUserId, NotificationType type, String title, String body, UUID projectId, Long actorUserId);

    List<NotificationResponse> list(Long userId);

    long unreadCount(Long userId);

    void markRead(Long userId, UUID id);

    void markAllRead(Long userId);
}
