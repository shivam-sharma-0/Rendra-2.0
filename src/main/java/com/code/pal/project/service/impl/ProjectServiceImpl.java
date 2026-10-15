package com.code.pal.project.service.impl;

import com.code.pal.identity.dto.UserSummary;
import com.code.pal.identity.service.UserDirectory;
import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.project.domain.DeploymentStatus;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectMember;
import com.code.pal.project.domain.ProjectRole;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.dto.ProjectMemberResponse;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.dto.UpdateProjectRequest;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.exception.AlreadyProjectMemberException;
import com.code.pal.project.exception.ProjectMemberNotFoundException;
import com.code.pal.project.exception.ProjectNotFoundException;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.ProjectMemberRepository;
import com.code.pal.project.repository.ProjectRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.workspace.service.WorkspaceService;
import com.code.pal.workspace.domain.WorkspaceMember;
import com.code.pal.workspace.domain.WorkspaceRole;
import com.code.pal.workspace.exception.WorkspaceAccessDeniedException;
import com.code.pal.shared.web.SitesOrigin;
import com.code.pal.shared.usage.AgentTurnAdmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Project logic. Access is authorized once via
 * {@link WorkspaceService#requireMembership}; reads
 * and writes are single indexed operations, and mutations dirty a managed
 * entity so JPA flushes
 * on commit â€” no redundant {@code save()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private static final int NAME_FROM_PROMPT_MAX = 60;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final MessageRepository messageRepository;
    private final MessageSequence messageSequence;
    private final WorkspaceService workspaceService;
    private final UserDirectory userDirectory;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;
    /** Published sites live on the separate user-content origin, never on the API origin. */
    private final SitesOrigin sites;
    private final AgentTurnAdmission admission;
    private final com.code.pal.agent.preview.PublishedSiteService publishedSites;

    @Override
    @Transactional
    public ProjectResponse create(Long userId, UUID workspaceId, CreateProjectRequest request) {
        workspaceService.requireMembership(userId, workspaceId);

        CreateMessageRequest initial = request.initialMessage();
        String firstPrompt = initial != null ? initial.message() : request.pendingPrompt();
        ProjectType type = request.type() != null ? request.type() : ProjectType.BUILD;
        boolean requirementsNeeded = type == ProjectType.BUILD && needsRequirements(firstPrompt);
        boolean startsAgentTurn = initial != null && !requirementsNeeded
                && initial.aiMessageId() != null && !initial.aiMessageId().isBlank();
        // Before anything is saved, so a rejected first turn does not leave an empty project behind.
        if (startsAgentTurn) admission.requireAdmitted(userId);

        Project project = projectRepository.save(Project.builder()
                .workspaceId(workspaceId)
                .ownerId(userId)
                .name(deriveName(request.name(), firstPrompt))
                .type(type)
                .lifecycleStage(requirementsNeeded ? ProjectStage.REQUIREMENTS : ProjectStage.DESIGN)
                .initialPrompt(firstPrompt)
                .lastActivityAt(Instant.now())
                .build());

        if (initial != null) {
            // Actionable BUILD prompts pause for requirements; greetings and short chat prompts answer normally.
            if (requirementsNeeded) seedInitialPrompt(project.getId(), initial, type, userId);
            else if (type == ProjectType.BUILD) {
                seedConversation(project.getId(), new CreateMessageRequest(initial.id(), initial.message(), ProjectType.CHAT, initial.aiMessageId()), type, userId);
            } else seedConversation(project.getId(), initial, type, userId);
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
    public ProjectResponse advanceLifecycle(Long userId, UUID projectId, ProjectStage stage) {
        Project project = requireCanEdit(userId, projectId);
        project.setLifecycleStage(stage);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public ProjectResponse update(Long userId, UUID projectId, UpdateProjectRequest request) {
        Project project = requireManageable(userId, projectId);

        if (request.name() != null)
            project.setName(request.name().trim());
        if (request.isPublic() != null)
            project.setIsPublic(request.isPublic());
        if (request.type() != null)
            project.setType(request.type());

        // Managed entity â€” changes flush on commit; no explicit save() needed.
        log.info("Project updated id={} owner={}", projectId, userId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public void delete(Long userId, UUID projectId) {
        Project project = requireManageable(userId, projectId);
        project.setDeletedAt(Instant.now()); // soft delete â€” hidden by @SQLRestriction thereafter
        // The agent module stops the project's runs and removes its files and sandbox after commit.
        events.publishEvent(new com.code.pal.project.event.ProjectDeleted(projectId));
        log.info("Project soft-deleted id={} owner={}", projectId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> deletedAmong(Collection<UUID> projectIds) {
        return projectIds.isEmpty() ? List.of() : projectRepository.findDeletedIdsIn(projectIds);
    }

    /**
     * Read access: any workspace member, or anyone the project was explicitly
     * shared with.
     */
    @Override
    @Transactional(readOnly = true)
    public Project requireAccessible(Long userId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
        if (workspaceService.isMember(userId, project.getWorkspaceId())
                || projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            return project;
        }
        throw new WorkspaceAccessDeniedException();
    }

    /**
     * Write access: workspace members, or a project collaborator with OWNER/EDITOR
     * (not VIEWER).
     */
    @Override
    @Transactional(readOnly = true)
    public Project requireCanEdit(Long userId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
        if (workspaceService.isMember(userId, project.getWorkspaceId()))
            return project;
        ProjectRole role = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(ProjectMember::getRole).orElse(null);
        if (role == ProjectRole.OWNER || role == ProjectRole.EDITOR)
            return project;
        throw new WorkspaceAccessDeniedException();
    }

    /**
     * Project creators may manage their own projects; workspace owners/admins may
     * manage all.
     */
    @Override
    @Transactional(readOnly = true)
    public Project requireManageable(Long userId, UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(ProjectNotFoundException::new);
        WorkspaceMember membership = workspaceService.requireMembership(userId, project.getWorkspaceId());
        boolean canManage = project.getOwnerId().equals(userId)
                || membership.getRole() == WorkspaceRole.OWNER
                || membership.getRole() == WorkspaceRole.ADMIN;
        if (!canManage)
            throw new WorkspaceAccessDeniedException();
        return project;
    }

    /* ── Per-project sharing ─────────────────────────────────────────────────── */

    @Override
    @Transactional(readOnly = true)
    public List<ProjectMemberResponse> listMembers(Long actorId, UUID projectId) {
        requireAccessible(actorId, projectId);
        List<ProjectMember> members = projectMemberRepository.findByProjectId(projectId);
        Map<Long, UserSummary> byId = userDirectory
                .findByIds(members.stream().map(ProjectMember::getUserId).toList())
                .stream().collect(Collectors.toMap(UserSummary::id, u -> u));
        return members.stream()
                .map(m -> ProjectMemberResponse.from(m, byId.get(m.getUserId())))
                .sorted((a, b) -> a.sharedAt().compareTo(b.sharedAt()))
                .toList();
    }

    @Override
    @Transactional
    public ProjectMemberResponse share(Long actorId, UUID projectId, String email, ProjectRole role) {
        Project project = requireManageable(actorId, projectId);
        UserSummary user = userDirectory.findByEmail(email)
                .orElseThrow(() -> new ProjectMemberNotFoundException("No Rendra account for that email"));
        if (projectMemberRepository.existsByProjectIdAndUserId(projectId, user.id())) {
            throw new AlreadyProjectMemberException("They already have access to this project");
        }
        ProjectMember member = projectMemberRepository.save(ProjectMember.builder()
                .projectId(projectId)
                .userId(user.id())
                .role(role != null ? role : ProjectRole.VIEWER)
                .build());
        notificationService.notify(user.id(), NotificationType.PROJECT_SHARED,
                "A project was shared with you", project.getName(), projectId, actorId);
        log.info("Project shared id={} with user={} role={} by={}", projectId, user.id(), member.getRole(), actorId);
        return ProjectMemberResponse.from(member, user);
    }

    @Override
    @Transactional
    public ProjectMemberResponse updateMemberRole(Long actorId, UUID projectId, UUID memberId, ProjectRole role) {
        requireManageable(actorId, projectId);
        ProjectMember member = requireMemberInProject(projectId, memberId);
        member.setRole(role);
        UserSummary user = userDirectory.findByIds(List.of(member.getUserId())).stream().findFirst().orElse(null);
        log.info("Project member role updated project={} member={} role={} by={}", projectId, memberId, role, actorId);
        return ProjectMemberResponse.from(member, user);
    }

    @Override
    @Transactional
    public void removeMember(Long actorId, UUID projectId, UUID memberId) {
        requireManageable(actorId, projectId);
        projectMemberRepository.delete(requireMemberInProject(projectId, memberId));
        log.info("Project member removed project={} member={} by={}", projectId, memberId, actorId);
    }

    private ProjectMember requireMemberInProject(UUID projectId, UUID memberId) {
        ProjectMember member = projectMemberRepository.findById(memberId)
                .orElseThrow(() -> new ProjectMemberNotFoundException("Collaborator not found"));
        if (!member.getProjectId().equals(projectId)) {
            throw new ProjectMemberNotFoundException("Collaborator not found");
        }
        return member;
    }

    /* ── Publish lifecycle ───────────────────────────────────────────────────── */

    @Override
    @Transactional
    public ProjectResponse publish(Long actorId, UUID projectId) {
        Project project = requireManageable(actorId, projectId);
        if (project.getType() == ProjectType.BUILD
                && project.getLifecycleStage() != ProjectStage.REVIEW
                && project.getLifecycleStage() != ProjectStage.LIVE) {
            throw new WorkflowStageException("Review the completed build before deploying it");
        }
        // Visitors get a snapshot of the site as built now; later agent runs only change the preview.
        try {
            publishedSites.publish(projectId);
        } catch (IllegalStateException notPublishable) {
            throw new WorkflowStageException(notPublishable.getMessage());
        }
        if (project.getPublicSlug() == null)
            project.setPublicSlug(uniqueSlug(project.getName()));
        project.setDeploymentStatus(DeploymentStatus.LIVE);
        project.setLifecycleStage(ProjectStage.LIVE);
        project.setIsPublic(true);
        project.setPublishedUrl(sites.publishedUrl(project.getPublicSlug()));
        log.info("Project published id={} slug={} by={}", projectId, project.getPublicSlug(), actorId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public ProjectResponse unpublish(Long actorId, UUID projectId) {
        Project project = requireManageable(actorId, projectId);
        project.setDeploymentStatus(DeploymentStatus.NONE);
        project.setIsPublic(false);
        publishedSites.unpublish(projectId);
        if (project.getType() == ProjectType.BUILD) project.setLifecycleStage(ProjectStage.REVIEW);
        log.info("Project unpublished id={} by={}", projectId, actorId);
        return ProjectResponse.from(project);
    }

    @Override
    @Transactional
    public String previewKey(UUID projectId) {
        Project project = projectRepository.findById(projectId).orElseThrow(ProjectNotFoundException::new);
        if (project.getPreviewKey() == null) {
            StringBuilder key = new StringBuilder(24);
            String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";   // a DNS label: lowercase letters and digits
            for (int i = 0; i < 24; i++) key.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            project.setPreviewKey(key.toString());
        }
        return project.getPreviewKey();
    }

    @Override
    @Transactional(readOnly = true)
    public UUID resolvePreviewKey(String previewKey) {
        return projectRepository.findByPreviewKey(previewKey).map(Project::getId).orElseThrow(ProjectNotFoundException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public UUID resolvePublishedProject(String slug) {
        return projectRepository.findByPublicSlug(slug)
                .filter(p -> p.getDeploymentStatus() == DeploymentStatus.LIVE)
                .map(Project::getId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private String uniqueSlug(String name) {
        String base = slugify(name);
        for (int i = 0; i < 5; i++) {
            String candidate = base + "-" + Integer.toHexString(RANDOM.nextInt(0x10000));
            if (projectRepository.findByPublicSlug(candidate).isEmpty())
                return candidate;
        }
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String slugify(String value) {
        String base = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank())
            base = "app";
        return base.length() > 40 ? base.substring(0, 40) : base;
    }

    /**
     * Insert the first user message and, if reserved, an empty assistant reply to
     * stream into.
     */
    private void seedConversation(UUID projectId, CreateMessageRequest initial, ProjectType projectType,
            Long authorId) {
        ProjectType mode = initial.mode() != null ? initial.mode() : projectType;
        Message userMessage = Message.user(projectId, initial.id(), initial.message(), mode, authorId);
        userMessage.setSeq(messageSequence.next());
        messageRepository.save(userMessage);
        if (initial.aiMessageId() != null && !initial.aiMessageId().isBlank()) {
            Message assistantMessage = Message.assistantPlaceholder(projectId, initial.aiMessageId(), initial.id(), mode);
            assistantMessage.setSeq(messageSequence.next());
            messageRepository.save(assistantMessage);
            // Kick off the agent for the first turn (runs AFTER_COMMIT).
            events.publishEvent(new AssistantTurnRequested(projectId, authorId, mode, initial.aiMessageId()));
        }
    }

    private void seedInitialPrompt(UUID projectId, CreateMessageRequest initial, ProjectType projectType,
            Long authorId) {
        Message userMessage = Message.user(projectId, initial.id(), initial.message(), projectType, authorId);
        userMessage.setSeq(messageSequence.next());
        messageRepository.save(userMessage);
    }

    private static String deriveName(String name, String firstPrompt) {
        if (name != null && !name.isBlank())
            return name.trim();
        if (firstPrompt != null && !firstPrompt.isBlank()) {
            String prompt = firstPrompt.trim();
            return prompt.length() <= NAME_FROM_PROMPT_MAX
                    ? prompt
                    : prompt.substring(0, NAME_FROM_PROMPT_MAX).trim() + "…";
        }
        return "Untitled project";
    }

    private static boolean needsRequirements(String firstPrompt) {
        if (firstPrompt == null) return false;
        String prompt = firstPrompt.trim().toLowerCase();
        if (prompt.isBlank() || prompt.matches("^(hi|hello|hey|yo|good morning|good evening|ok|okay)[.! ]*$")) return false;
        return prompt.length() >= 9 && prompt.matches(".*\\b(build|create|make|develop|design|implement|generate|clone|app|application|website|dashboard|todo|tool|platform|system|project|page|portal|crm|portfolio|store|shop|blog|mern)\\b.*");
    }
}
