package com.code.pal.message.service.impl;

import com.code.pal.message.MessageSequence;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageFeedback;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.exception.MessageNotFoundException;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.service.ProjectService;
import com.code.pal.project.exception.RequirementApprovalRequiredException;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.shared.usage.AgentTurnAdmission;
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
    private final MessageSequence sequence;
    private final AgentTurnAdmission admission;
    private final com.code.pal.agent.workspace.ProjectFiles files;

    @Override
    @Transactional
    public List<MessageResponse> post(Long userId, UUID projectId, CreateMessageRequest request) {
        // Posting drives the agent (a write), so VIEWER-only collaborators are read-only here.
        Project project = projectService.requireCanEdit(userId, projectId);

        // Idempotent retries may return only a message from this project. A global id lookup here
        // would expose a message from another project to a caller who merely knows its client id.
        var existing = messageRepository.findByIdAndProjectId(request.id(), projectId);
        if (existing.isPresent()) {
            Message accepted = existing.get();
            List<String> retryAttachments = retryAttachmentPaths(request.attachments());
            if (accepted.getRole() != com.code.pal.message.domain.MessageRole.USER
                    || !java.util.Objects.equals(accepted.getAuthorId(), userId)
                    || !java.util.Objects.equals(accepted.getContent(), request.message())
                    || !accepted.attachmentPaths().equals(retryAttachments)
                    || (request.mode() != null && accepted.getRequestedMode() != null
                        && request.mode() != accepted.getRequestedMode()))
                throw new com.code.pal.message.exception.MessageConflictException();
            var assistant = messageRepository.findFirstByProjectIdAndReplyToIdAndRoleOrderBySeqAsc(
                    projectId, request.id(), com.code.pal.message.domain.MessageRole.ASSISTANT);
            if (assistant.isPresent() && !assistant.get().getId().equals(request.aiMessageId()))
                throw new com.code.pal.message.exception.MessageConflictException();
            List<MessageResponse> result = new ArrayList<>();
            result.add(MessageResponse.from(accepted));
            assistant.ifPresent(message -> result.add(MessageResponse.from(message)));
            return List.copyOf(result);
        }

        ProjectType mode = request.mode() != null ? request.mode() : project.getType();
        ProjectType requestedMode = mode;
        mode = com.code.pal.agent.intent.RequestScope.mode(mode, request.message());
        // The opening prompt of a project created with pendingPrompt (its attachments uploaded first):
        // stored for the requirements step, which builds from it once approved, so no turn starts now.
        boolean openingPrompt = false;
        if (mode == ProjectType.BUILD && project.getLifecycleStage() == ProjectStage.REQUIREMENTS) {
            if (isConversationalPrompt(request.message())) {
                project.setLifecycleStage(ProjectStage.DESIGN);
                mode = ProjectType.CHAT;
            } else if (messageRepository.countByProjectId(projectId) == 0) {
                openingPrompt = true;
            } else {
                throw new RequirementApprovalRequiredException();
            }
        }

        boolean startsAgentTurn = !openingPrompt && request.aiMessageId() != null && !request.aiMessageId().isBlank();
        // Check limits before anything is saved, so a rejected turn leaves no orphan message behind.
        if (startsAgentTurn) admission.requireAdmitted(userId);

        List<Message> created = new ArrayList<>(2);
        Message userMessage = Message.user(projectId, request.id(), request.message(), mode, userId);
        userMessage.setRequestedMode(requestedMode);
        userMessage.setAttachments(attachments(projectId, request.attachments()));
        userMessage.setSeq(sequence.next());
        created.add(messageRepository.save(userMessage));
        if (startsAgentTurn) {
            Message assistantMessage = Message.assistantPlaceholder(projectId, request.aiMessageId(), request.id(), mode);
            assistantMessage.setSeq(sequence.next());
            created.add(messageRepository.save(assistantMessage));
            // Persist run/outbox in this transaction; deliver only once acceptance commits.
            events.publishEvent(new AssistantTurnRequested(projectId, userId, mode, request.aiMessageId()));
        }

        project.setLastActivityAt(Instant.now());   // managed entity — flushes on commit
        // Ids are client-assigned, so Hibernate defers the INSERT (and @CreationTimestamp) to flush.
        // Flush now or the response/broadcast below carries createdAt=null and the client mis-sorts it.
        messageRepository.flush();
        log.info("Message posted project={} user={} id={}", projectId, userId, request.id());

        // Broadcast to everyone in the project room — but only AFTER the tx commits, so a rollback
        // never leaves collaborators showing a message that was never persisted.
        List<MessageResponse> responses = created.stream().map(MessageResponse::from).toList();
        broadcastAfterCommit(projectId, responses);
        return responses;
    }

    /**
     * Attachments are files the user uploaded to this project ({@code uploads/<file>}); anything else,
     * or a file that does not exist, is rejected rather than silently dropped.
     */
    private String attachments(UUID projectId, java.util.List<String> paths) {
        if (paths == null || paths.isEmpty()) return null;
        java.util.List<String> valid = new java.util.ArrayList<>();
        for (String path : paths) {
            String normalized;
            try {
                normalized = com.code.pal.agent.workspace.ProjectPaths.normalize(path, false);
            } catch (IllegalArgumentException invalid) {
                throw new com.code.pal.message.exception.InvalidAttachmentException("Invalid attachment: " + path);
            }
            if (!normalized.matches("uploads/[^/]+") || !files.regularFile(projectId, ".", normalized)) {
                throw new com.code.pal.message.exception.InvalidAttachmentException("Not an uploaded file of this project: " + path);
            }
            if (!valid.contains(normalized)) valid.add(normalized);
        }
        return String.join("\n", valid);
    }

    private static List<String> retryAttachmentPaths(List<String> paths) {
        if (paths == null) return List.of();
        try {
            return paths.stream().map(path -> com.code.pal.agent.workspace.ProjectPaths.normalize(path, false))
                    .distinct().toList();
        } catch (IllegalArgumentException invalid) {
            throw new com.code.pal.message.exception.MessageConflictException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> openingAttachments(UUID projectId) {
        return messageRepository.findFirstByProjectIdAndRoleOrderBySeqAsc(projectId, com.code.pal.message.domain.MessageRole.USER)
                .map(Message::attachmentPaths).orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> list(Long userId, UUID projectId, Pageable pageable) {
        projectService.requireAccessible(userId, projectId);   // authorize
        return messageRepository.findByProjectId(projectId, pageable).map(MessageResponse::from);
    }

    @Override
    @Transactional
    public MessageResponse setFeedback(Long userId, UUID projectId, String messageId, MessageFeedback feedback) {
        projectService.requireAccessible(userId, projectId);
        Message message = messageRepository.findByIdAndProjectId(messageId, projectId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found"));
        message.setFeedback(feedback);
        MessageResponse response = MessageResponse.from(messageRepository.save(message));
        broadcastAfterCommit(projectId, List.of(response));
        return response;
    }

    private static boolean isConversationalPrompt(String message) {
        if (message == null) return true;
        String prompt = message.trim().toLowerCase();
        if (com.code.pal.agent.intent.RequestScope.action(prompt)) return false;
        if (prompt.isBlank() || prompt.matches("^(hi|hello|hey|yo|good morning|good evening|ok|okay)[.! ]*$")) return true;
        return !prompt.matches(".*\\b(build|create|make|develop|design|implement|generate|clone|app|application|website|dashboard|todo|tool|platform|system|project|page|portal|crm|portfolio|store|shop|blog|mern)\\b.*");
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
