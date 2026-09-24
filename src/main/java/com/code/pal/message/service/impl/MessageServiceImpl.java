package com.code.pal.message.service.impl;

import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private final MessageRepository messageRepository;
    private final ProjectService projectService;
    private final RealtimeService realtimeService;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public List<MessageResponse> post(Long userId, UUID projectId, CreateMessageRequest request) {
        Project project = projectService.requireAccessible(userId, projectId);

        // Idempotent retry: the id already landed — return it instead of inserting a duplicate.
        if (messageRepository.existsById(request.id())) {
            return messageRepository.findById(request.id())
                    .map(m -> List.of(MessageResponse.from(m)))
                    .orElseGet(List::of);
        }

        ProjectType mode = request.mode() != null ? request.mode() : project.getType();

        List<Message> created = new ArrayList<>(2);
        created.add(messageRepository.save(Message.user(projectId, request.id(), request.message(), mode, userId)));
        if (request.aiMessageId() != null && !request.aiMessageId().isBlank()) {
            created.add(messageRepository.save(
                    Message.assistantPlaceholder(projectId, request.aiMessageId(), request.id(), mode)));
            // Kick off the agent to fill this assistant placeholder (runs AFTER_COMMIT).
            events.publishEvent(new AssistantTurnRequested(projectId, userId, mode, request.aiMessageId()));
        }

        project.setLastActivityAt(Instant.now());   // managed entity — flushes on commit
        log.info("Message posted project={} user={} id={}", projectId, userId, request.id());

        // Broadcast to everyone in the project room — but only AFTER the tx commits, so a rollback
        // never leaves collaborators showing a message that was never persisted.
        List<MessageResponse> responses = created.stream().map(MessageResponse::from).toList();
        broadcastAfterCommit(projectId, responses);
        return responses;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> list(Long userId, UUID projectId, Pageable pageable) {
        projectService.requireAccessible(userId, projectId);   // authorize
        return messageRepository.findByProjectId(projectId, pageable).map(MessageResponse::from);
    }

    /** Fire the room broadcast once the surrounding transaction commits (falls back to inline if none). */
    private void broadcastAfterCommit(UUID projectId, List<MessageResponse> messages) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            messages.forEach(m -> realtimeService.sendToProject(projectId, RealtimeMessage.of("MESSAGE_UPSERT", m)));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messages.forEach(m -> realtimeService.sendToProject(projectId, RealtimeMessage.of("MESSAGE_UPSERT", m)));
            }
        });
    }
}
