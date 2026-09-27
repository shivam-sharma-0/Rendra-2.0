package com.code.pal.notification.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * A single activity item addressed to one recipient. Stored per-recipient (fan-out on write) so the
 * feed and unread count are simple indexed reads. References to other entities are plain ids to keep
 * the module boundary clean.
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "ix_notif_recipient_created", columnList = "recipient_user_id, created_at"),
                @Index(name = "ix_notif_recipient_unread", columnList = "recipient_user_id, read_at")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    UUID id;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    NotificationType type;

    @Column(nullable = false, length = 200, updatable = false)
    String title;

    @Column(length = 500, updatable = false)
    String body;

    /** Optional related project (for deep-linking the feed item). */
    @Column(name = "project_id", updatable = false)
    UUID projectId;

    /** Who triggered it (optional). */
    @Column(name = "actor_user_id", updatable = false)
    Long actorUserId;

    @Column(name = "read_at")
    Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;
}
