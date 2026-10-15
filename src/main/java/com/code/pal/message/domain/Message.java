package com.code.pal.message.domain;

import com.code.pal.project.domain.ProjectType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One turn in a project's conversation.
 *
 * <p><b>Client-assigned id</b>: the frontend generates the id (e.g. {@code umsg_…}, {@code aimsg_…})
 * so it can render the message optimistically, retry idempotently, and match a streaming reply to
 * the row it already placed. Because the id is not DB-generated, {@link Persistable#isNew()} tells
 * Hibernate to INSERT without a pre-SELECT — see {@link #isNew()}.
 *
 * <p><b>Ownership</b>: reachable only through its {@code projectId}; access is enforced one level up
 * (the project's workspace membership), so there is no user id here.
 */
@Entity
@Table(
        name = "messages",
        indexes = @Index(name = "ix_messages_project_created", columnList = "project_id, created_at")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Message implements Persistable<String> {

    @Id
    @Column(length = 40)
    String id;

    @Column(name = "project_id", nullable = false, updatable = false)
    UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    MessageRole role;

    @Column(nullable = false, columnDefinition = "text")
    @Builder.Default
    String content = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    MessageStatus status = MessageStatus.COMPLETE;

    /** The mode this turn ran in — may differ from the project default. Null if unspecified. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    ProjectType mode;

    /** Original requested mode, before conversational/requirements routing changes the effective mode. */
    @Enumerated(EnumType.STRING)
    @Column(name = "requested_mode", length = 16, updatable = false)
    ProjectType requestedMode;

    /** The user who authored this message; null for assistant/system messages (collaboration). */
    @Column(name = "author_id")
    Long authorId;

    /** For an assistant reply: the id of the user message it answers. Null otherwise. */
    @Column(name = "reply_to_id", length = 40)
    String replyToId;

    /** The reacting user's thumbs up/down on this message, if any. */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    MessageFeedback feedback;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    /** Tie-breaker for display order: a user turn and its assistant placeholder are created back to
     * back and can land on the exact same {@code createdAt} instant (JVM clock resolution), which
     * otherwise leaves their relative order to chance on a re-fetch. Assigned by {@link
     * com.code.pal.message.MessageSequence}, not the database, so it stays correct across a restart
     * (a fresh counter still sorts after every pre-restart row, since createdAt is compared first). */
    @Column(name = "seq", nullable = false) @ColumnDefault("0") @Builder.Default long seq = 0;

    /** Project paths (uploads/...) attached to a user message, one per line; null when there are none. */
    @Column(columnDefinition = "text")
    String attachments;

    public java.util.List<String> attachmentPaths() {
        return attachments == null || attachments.isBlank() ? java.util.List.of() : java.util.List.of(attachments.split("\n"));
    }

    /** New until persisted (id is assigned, so Hibernate can't infer this itself). */
    @Override
    public boolean isNew() {
        return createdAt == null;
    }

    /* ── Factories ─────────────────────────────────────────────────────────── */

    /** A completed user message, tagged with its author (for multi-user projects). */
    public static Message user(UUID projectId, String id, String content, ProjectType mode, Long authorId) {
        return Message.builder()
                .id(id).projectId(projectId).role(MessageRole.USER)
                .content(content).status(MessageStatus.COMPLETE).mode(mode).authorId(authorId)
                .build();
    }

    /** An empty assistant reply, waiting to be streamed into. */
    public static Message assistantPlaceholder(UUID projectId, String id, String replyToId, ProjectType mode) {
        return Message.builder()
                .id(id).projectId(projectId).role(MessageRole.ASSISTANT)
                .content("").status(MessageStatus.PENDING).replyToId(replyToId).mode(mode)
                .build();
    }
}
