package com.code.pal.notification.service.impl;

import com.code.pal.notification.domain.Notification;
import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.dto.NotificationResponse;
import com.code.pal.notification.repository.NotificationRepository;
import com.code.pal.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;

    /**
     * Best-effort write in its own transaction: a notification failure (or a rollback of the caller's
     * tx) must never affect the primary action that raised it.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long recipientUserId, NotificationType type, String title, String body, UUID projectId, Long actorUserId) {
        if (recipientUserId == null) return;
        try {
            notifications.save(Notification.builder()
                    .recipientUserId(recipientUserId)
                    .type(type)
                    .title(truncate(title, 200))
                    .body(truncate(body, 500))
                    .projectId(projectId)
                    .actorUserId(actorUserId)
                    .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to raise notification type={} recipient={}: {}", type, recipientUserId, ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId) {
        return notifications.findTop50ByRecipientUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
    }

    @Override
    @Transactional
    public void markRead(Long userId, UUID id) {
        notifications.findByIdAndRecipientUserId(id, userId)
                .filter(n -> n.getReadAt() == null)
                .ifPresent(n -> n.setReadAt(Instant.now()));
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        notifications.markAllRead(userId, Instant.now());
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
