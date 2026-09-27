package com.code.pal.message.service;

import com.code.pal.message.domain.MessageFeedback;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.dto.MessageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Conversation use-cases. Access is authorized through the project's workspace membership
 * (delegated to the project module), so there is no separate ownership concept here.
 */
public interface MessageService {

    /**
     * Append a user turn (and optionally reserve an assistant reply row). Idempotent on the
     * client-supplied id, so a retried request never duplicates a message.
     */
    List<MessageResponse> post(Long userId, UUID projectId, CreateMessageRequest request);

    /** A project's conversation, oldest-first. */
    Page<MessageResponse> list(Long userId, UUID projectId, Pageable pageable);

    /** Set (or clear, when {@code feedback} is null) the caller's reaction to a message. */
    MessageResponse setFeedback(Long userId, UUID projectId, String messageId, MessageFeedback feedback);
}
