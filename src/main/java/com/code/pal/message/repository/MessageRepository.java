package com.code.pal.message.repository;

import com.code.pal.message.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.List;

/**
 * Message persistence. Listing is keyed on {@code project_id} and rides the
 * {@code (project_id, created_at)} index, so a conversation loads in order via one range scan.
 */
public interface MessageRepository extends JpaRepository<Message, String> {

    Page<Message> findByProjectId(UUID projectId, Pageable pageable);

    /** Bound before pagination: later queued turns must not displace this run's context. */
    @Query("select m from Message m where m.projectId = :projectId and "
            + "(m.createdAt < :createdAt or (m.createdAt = :createdAt and m.seq <= :seq))")
    Page<Message> findHistoryThrough(@Param("projectId") UUID projectId,
                                    @Param("createdAt") Instant createdAt,
                                    @Param("seq") long seq, Pageable pageable);

    /** A client retry is idempotent only inside the project that originally created the message. */
    Optional<Message> findByIdAndProjectId(String id, UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Message m where m.id = :id and m.projectId = :projectId")
    Optional<Message> lockByIdAndProjectId(String id, UUID projectId);

    Optional<Message> findFirstByProjectIdAndReplyToIdAndRoleOrderBySeqAsc(
            UUID projectId, String replyToId, com.code.pal.message.domain.MessageRole role);

    long countByProjectId(UUID projectId);

    /** Include consumed rows: falling back to an older unconsumed offer would replay stale work. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Message a, Message u where a.projectId=:projectId and u.projectId=:projectId "
            + "and a.replyToId=u.id and u.authorId=:userId and u.role='USER' and a.role='ASSISTANT' and a.status='COMPLETE' "
            + "and a.suggestedBuildText is not null "
            + "and exists (select r.id from AgentRun r where r.projectId=a.projectId and r.messageId=a.id and r.status='DONE') "
            + "order by a.createdAt desc, a.seq desc")
    List<Message> lockLatestProposal(@Param("projectId") UUID projectId, @Param("userId") Long userId,
                                              Pageable pageable);

    /** The project's first message by the given role (its opening prompt, for USER). */
    Optional<Message> findFirstByProjectIdAndRoleOrderBySeqAsc(UUID projectId, com.code.pal.message.domain.MessageRole role);
}
