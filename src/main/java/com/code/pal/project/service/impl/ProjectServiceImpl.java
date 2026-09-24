package com.code.pal.project.service.impl;

import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.dto.UpdateProjectRequest;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.exception.ProjectNotFoundException;
import com.code.pal.project.repository.ProjectRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/**
 * Project logic. Access is authorized once via {@link WorkspaceService#requireMembership}; reads
 * and writes are single indexed operations, and mutations dirty a managed entity so JPA flushes
 * on commit — no redundant {@code save()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private static final int NAME_FROM_PROMPT_MAX = 60;

    private final ProjectRepository projectRepository;
    private final MessageRepository messageRepository;
    private final WorkspaceService workspaceService;
    private final ApplicationEventPublisher events;

    @Override
    @Transactional
    public ProjectResponse create(Long userId, UUID workspaceId, CreateProjectRequest request) {
        workspaceService.requireMembership(userId, workspaceId);

        CreateMessageRequest initial = request.initialMessage();
        ProjectType type = request.type() != null ? request.type() : ProjectType.BUILD;

        Project project = projectRepository.save(Project.builder()
                .workspaceId(workspaceId)
                .ownerId(userId)
                .name(deriveName(request.name(), initial))
                .type(type)
                .initialPrompt(initial != null ? initial.message() : null)
                .lastActivityAt(Instant.now())
                .build());

        if (initial != null) {
            seedConversation(project.getId(), initial, type, userId);
        }

        log.info("Project created id={} workspace={} owner={}", project.getId(), workspaceId, userId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProjectResponse> list(Long userId, UUID workspaceId, Collection<ProjectType> types, Pageable pageable) {
        workspaceService.requireMembership(userId, workspaceId);
        Page<Project> page = (types == null || types.isEmpty())
                ? projectRepository.findByWorkspaceId(workspaceId, pageable)
                : projectRepository.findByWorkspaceIdAndTypeIn(workspaceId, types, pageable);
        return page.map(ProjectResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse get(Long userId, UUID projectId) {
        return ProjectResponse.from(requireAccessible(userId, projectId));
    }

    @Override
    @Transactional
    public ProjectResponse update(Long userId, UUID projectId, UpdateProjectRequest request) {
        Project project = requireAccessible(userId, projectId);

        if (request.name() != null) project.setName(request.name().trim());
        if (request.isPublic() != null) project.setIsPublic(request.isPublic());
        if (request.type() != null) project.setType(request.type());

        // Managed entity — changes flush on commit; no explicit save() needed.
        log.info("Project updated id={} owner={}", projectId, userId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public void delete(Long userId, UUID projectId) {
        Project project = requireAccessible(userId, projectId);
        project.setDeletedAt(Instant.now());   // soft delete — hidden by @SQLRestriction thereafter
        log.info("Project soft-deleted id={} owner={}", projectId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Project requireAccessible(Long userId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
        workspaceService.requireMembership(userId, project.getWorkspaceId());
        return project;
    }

    /** Insert the first user message and, if reserved, an empty assistant reply to stream into. */
    private void seedConversation(UUID projectId, CreateMessageRequest initial, ProjectType projectType, Long authorId) {
        ProjectType mode = initial.mode() != null ? initial.mode() : projectType;
        messageRepository.save(Message.user(projectId, initial.id(), initial.message(), mode, authorId));
        if (initial.aiMessageId() != null && !initial.aiMessageId().isBlank()) {
            messageRepository.save(Message.assistantPlaceholder(projectId, initial.aiMessageId(), initial.id(), mode));
            // Kick off the agent for the first turn (runs AFTER_COMMIT).
            events.publishEvent(new AssistantTurnRequested(projectId, authorId, mode, initial.aiMessageId()));
        }
    }

    private static String deriveName(String name, CreateMessageRequest initial) {
        if (name != null && !name.isBlank()) return name.trim();
        if (initial != null && !initial.message().isBlank()) {
            String prompt = initial.message().trim();
            return prompt.length() <= NAME_FROM_PROMPT_MAX
                    ? prompt
                    : prompt.substring(0, NAME_FROM_PROMPT_MAX).trim() + "…";
        }
        return "Untitled project";
    }
}
