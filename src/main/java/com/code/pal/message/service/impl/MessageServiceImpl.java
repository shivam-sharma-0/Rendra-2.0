package com.code.pal.message.service.impl;

import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.domain.Message;
import com.code.pal.message.domain.MessageFeedback;
import com.code.pal.message.domain.MessageRole;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.dto.MessageResponse;
import com.code.pal.message.exception.MessageNotFoundException;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.ConversationProposals;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.exception.RequirementApprovalRequiredException;
import com.code.pal.project.service.ProjectService;
import com.code.pal.project.requirement.BuildRequestIntake;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.realtime.dto.RealtimeMessage;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.workspace.exception.WorkspaceAccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final ConversationProposals proposals;
    private final BuildRequestIntake buildIntake;

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
            if (accepted.getRole() != MessageRole.USER
                    || !Objects.equals(accepted.getAuthorId(), userId)
                    || !Objects.equals(accepted.getContent(), request.message())
                    || !accepted.attachmentPaths().equals(retryAttachments)
                    || (request.proposalId() != null && !request.proposalId().equals(accepted.getSourceProposalId()))
                    || (request.mode() != null && accepted.getRequestedMode() != null
                        && request.mode() != accepted.getRequestedMode()))
                throw new com.code.pal.message.exception.MessageConflictException();
            var assistant = messageRepository.findFirstByProjectIdAndReplyToIdAndRoleOrderBySeqAsc(
                    projectId, request.id(), MessageRole.ASSISTANT);
            if (assistant.isPresent() && !assistant.get().getId().equals(request.aiMessageId()))
                throw new com.code.pal.message.exception.MessageConflictException();
            List<MessageResponse> result = new ArrayList<>();
            result.add(MessageResponse.from(accepted));
            assistant.ifPresent(message -> result.add(responseFor(userId, projectId, message)));
            return List.copyOf(result);
        }

        ProjectType mode = request.mode() != null ? request.mode() : project.getType();
        ProjectType requestedMode = mode;
        var resolution = proposals.resolve(userId, projectId, request, requestedMode);
        mode = RequestRouter.mode(mode, resolution.prompt(), resolution.attachments());
        String uploadedAttachments = attachments(projectId, request.attachments());
        String resolvedAttachments = resolution.proposal() == null ? null : attachments(projectId, resolution.attachments());
        // The opening prompt of a project created with pendingPrompt (its attachments uploaded first):
        // stored for the requirements step, which builds from it once approved, so no turn starts now.
        boolean openingPrompt = buildIntake.prepare(project, mode, resolution.prompt());
        if (!openingPrompt && mode == ProjectType.BUILD && project.getLifecycleStage() == ProjectStage.REQUIREMENTS) {
            if (messageRepository.countByProjectId(projectId) == 0) {
                openingPrompt = true;
            } else {
                throw new RequirementApprovalRequiredException();
            }
        }

        boolean startsAgentTurn = !openingPrompt && request.aiMessageId() != null && !request.aiMessageId().isBlank();
        // Check limits before anything is saved, so a rejected turn leaves no orphan message behind.
        if (startsAgentTurn) admission.requireAdmitted(userId);

        List<Message> retired = List.of();
        if (resolution.proposal() == null
                && (RequestRouter.route(request.message(), request.attachments(), true) != RequestRouter.Route.CONTINUE
                    || request.attachments() != null && !request.attachments().isEmpty())) {
            retired = proposals.retirePrevious(projectId, request.id());
        }

        List<Message> created = new ArrayList<>(2);
        Message userMessage = Message.user(projectId, request.id(), request.message(), mode, userId);
        userMessage.setRequestedMode(requestedMode);
        userMessage.setAttachments(uploadedAttachments);
        if (resolution.proposal() != null) {
            userMessage.setSourceProposalId(resolution.proposal().getId());
            userMessage.setResolvedRequestText(resolution.prompt());
            userMessage.setResolvedAttachments(resolvedAttachments == null ? "" : resolvedAttachments);
        }
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
        if (openingPrompt) broadcastProjectAfterCommit(project);
        if (resolution.proposal() != null && resolution.proposal().getProposalConsumedBy() != null) {
            broadcastAfterCommit(projectId, List.of(MessageResponse.from(resolution.proposal())));
        }
        broadcastAfterCommit(projectId, retired.stream().map(MessageResponse::from).toList());
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
        return messageRepository.findFirstByProjectIdAndRoleAndModeOrderBySeqAsc(projectId, MessageRole.USER, ProjectType.BUILD)
                .or(() -> messageRepository.findFirstByProjectIdAndRoleOrderBySeqAsc(projectId, MessageRole.USER))
                .map(Message::executionAttachmentPaths).orElse(List.of());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MessageResponse> list(Long userId, UUID projectId, Pageable pageable) {
        projectService.requireAccessible(userId, projectId);   // authorize
        String eligible = eligibleProposal(userId, projectId);
        return messageRepository.findByProjectId(projectId, pageable).map(message -> {
            var response = MessageResponse.from(message);
            return message.getId().equals(eligible) ? response : response.withoutSuggestedAction();
        });
    }

    private MessageResponse responseFor(Long userId, UUID projectId, Message message) {
        var response = MessageResponse.from(message);
        return message.getId().equals(eligibleProposal(userId, projectId)) ? response : response.withoutSuggestedAction();
    }

    private String eligibleProposal(Long userId, UUID projectId) {
        try {
            projectService.requireCanEdit(userId, projectId);
        } catch (WorkspaceAccessDeniedException | AccessDeniedException denied) {
            return null;
        }
        var latest = messageRepository.findLatestProposal(projectId, userId, PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return null;
        }
        Message reply = latest.getFirst();
        if (MessageResponse.from(reply).suggestedAction() == null) {
            return null;
        }
        return reply.getId();
    }

    @Override
    @Transactional
    public MessageResponse setFeedback(Long userId, UUID projectId, String messageId, MessageFeedback feedback) {
        projectService.requireAccessible(userId, projectId);
        Message message = messageRepository.findByIdAndProjectId(messageId, projectId)
                .orElseThrow(() -> new MessageNotFoundException("Message not found"));
        message.setFeedback(feedback);
        MessageResponse response = responseFor(userId, projectId, messageRepository.save(message));
        broadcastAfterCommit(projectId, List.of(response));
        return response;
    }

    private void broadcastProjectAfterCommit(Project project) {
        var update = RealtimeMessage.of("PROJECT_UPDATED", ProjectResponse.from(project));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            realtimeService.sendToProject(project.getId(), update);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { realtimeService.sendToProject(project.getId(), update); }
        });
    }

    /** Fire the room broadcast once the surrounding transaction commits (falls back to inline if none). */
    private void broadcastAfterCommit(UUID projectId, List<MessageResponse> messages) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            messages.forEach(m -> realtimeService.sendToProject(projectId,
                    RealtimeMessage.of("MESSAGE_UPSERT", m.withoutSuggestedAction())));
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messages.forEach(m -> realtimeService.sendToProject(projectId,
                        RealtimeMessage.of("MESSAGE_UPSERT", m.withoutSuggestedAction())));
            }
        });
    }
}
