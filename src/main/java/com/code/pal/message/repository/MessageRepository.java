package com.code.pal.message.repository;

import com.code.pal.message.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Message persistence. Listing is keyed on {@code project_id} and rides the
 * {@code (project_id, created_at)} index, so a conversation loads in order via one range scan.
 */
public interface MessageRepository extends JpaRepository<Message, String> {

    Page<Message> findByProjectId(UUID projectId, Pageable pageable);
}
