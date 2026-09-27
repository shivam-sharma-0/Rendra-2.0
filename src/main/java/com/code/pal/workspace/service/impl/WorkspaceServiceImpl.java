package com.code.pal.workspace.service.impl;

import com.code.pal.identity.dto.UserSummary;
import com.code.pal.identity.service.UserDirectory;
import com.code.pal.notification.domain.NotificationType;
import com.code.pal.notification.service.NotificationService;
import com.code.pal.workspace.domain.InviteStatus;
import com.code.pal.workspace.domain.Workspace;
import com.code.pal.workspace.domain.WorkspaceInvite;
import com.code.pal.workspace.domain.WorkspaceMember;
import com.code.pal.workspace.domain.WorkspaceRole;
import com.code.pal.workspace.dto.CreateWorkspaceRequest;
import com.code.pal.workspace.dto.InvitePreviewResponse;
import com.code.pal.workspace.dto.MemberResponse;
import com.code.pal.workspace.dto.WorkspaceInviteResponse;
import com.code.pal.workspace.dto.WorkspaceResponse;
import com.code.pal.workspace.exception.AlreadyMemberException;
import com.code.pal.workspace.exception.InviteInvalidException;
import com.code.pal.workspace.exception.MemberNotFoundException;
import com.code.pal.workspace.exception.WorkspaceAccessDeniedException;
import com.code.pal.workspace.exception.WorkspaceNotFoundException;
import com.code.pal.workspace.exception.WorkspaceOperationException;
import com.code.pal.workspace.repository.WorkspaceInviteRepository;
import com.code.pal.workspace.repository.WorkspaceMemberRepository;
import com.code.pal.workspace.repository.WorkspaceRepository;
import com.code.pal.workspace.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceServiceImpl implements WorkspaceService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITE_TTL = Duration.ofDays(7);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;
    private final WorkspaceInviteRepository inviteRepository;
    private final UserDirectory userDirectory;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public WorkspaceResponse getOrCreatePersonal(Long userId) {
        return workspaceRepository.findByOwnerIdAndPersonalTrue(userId)
                .map(w -> WorkspaceResponse.from(w, WorkspaceRole.OWNER))
                .orElseGet(() -> {
                    Workspace w = provision("Personal", userId, true);
                    log.info("Personal workspace created id={} owner={}", w.getId(), userId);
                    return WorkspaceResponse.from(w, WorkspaceRole.OWNER);
                });
    }

    @Override
    @Transactional
    public List<WorkspaceResponse> listForUser(Long userId) {
        getOrCreatePersonal(userId);   // guarantee at least the personal workspace exists

        List<WorkspaceMember> memberships = memberRepository.findByUserId(userId);
        Map<UUID, WorkspaceRole> roleByWorkspace = memberships.stream()
                .collect(Collectors.toMap(WorkspaceMember::getWorkspaceId, WorkspaceMember::getRole));

        // Single batch load; soft-deleted workspaces are dropped by @SQLRestriction.
        return workspaceRepository.findAllById(roleByWorkspace.keySet()).stream()
                .map(w -> WorkspaceResponse.from(w, roleByWorkspace.get(w.getId())))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    @Override
    @Transactional
    public WorkspaceResponse create(Long userId, CreateWorkspaceRequest request) {
        Workspace w = provision(request.name().trim(), userId, false);
        log.info("Workspace created id={} owner={}", w.getId(), userId);
        return WorkspaceResponse.from(w, WorkspaceRole.OWNER);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceResponse get(Long actorId, UUID workspaceId) {
        WorkspaceMember member = requireMembership(actorId, workspaceId);
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
        return WorkspaceResponse.from(workspace, member.getRole());
    }

    @Override
    @Transactional
    public WorkspaceResponse rename(Long actorId, UUID workspaceId, String name) {
        WorkspaceMember member = requireRole(actorId, workspaceId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
        workspace.setName(name.trim());
        log.info("Workspace renamed id={} by={}", workspaceId, actorId);
        return WorkspaceResponse.from(workspace, member.getRole());
    }

    @Override
    @Transactional
    public void transferOwnership(Long actorId, UUID workspaceId, UUID newOwnerMemberId) {
        WorkspaceMember current = requireRole(actorId, workspaceId, WorkspaceRole.OWNER);
        WorkspaceMember target = requireMemberInWorkspace(workspaceId, newOwnerMemberId);
        if (target.getUserId().equals(actorId)) {
            throw new WorkspaceOperationException("They are already the owner");
        }
        // Effective ownership is the OWNER-role member; swap roles (no second OWNER left behind).
        target.setRole(WorkspaceRole.OWNER);
        current.setRole(WorkspaceRole.ADMIN);
        log.info("Ownership transferred workspace={} from={} to={}", workspaceId, actorId, target.getUserId());
    }

    @Override
    @Transactional
    public void leave(Long actorId, UUID workspaceId) {
        WorkspaceMember member = requireMembership(actorId, workspaceId);
        if (member.getRole() == WorkspaceRole.OWNER) {
            throw new WorkspaceOperationException("Transfer ownership before leaving this workspace");
        }
        memberRepository.delete(member);
        log.info("Member left workspace={} user={}", workspaceId, actorId);
    }

    @Override
    @Transactional
    public void delete(Long actorId, UUID workspaceId) {
        requireRole(actorId, workspaceId, WorkspaceRole.OWNER);
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
        if (workspace.isPersonal()) {
            throw new WorkspaceOperationException("You can't delete your personal workspace");
        }
        workspace.setDeletedAt(Instant.now());   // soft-delete; access revoked via findActiveMembership
        log.info("Workspace soft-deleted id={} by={}", workspaceId, actorId);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkspaceMember requireMembership(Long userId, UUID workspaceId) {
        return memberRepository.findActiveMembership(workspaceId, userId)
                .orElseThrow(() -> {
                    // Distinguish "gone" (or soft-deleted) from "not yours" without leaking on the happy path.
                    if (!workspaceRepository.existsById(workspaceId)) return new WorkspaceNotFoundException();
                    return new WorkspaceAccessDeniedException();
                });
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMember(Long userId, UUID workspaceId) {
        return memberRepository.findActiveMembership(workspaceId, userId).isPresent();
    }

    /* ── Members ─────────────────────────────────────────────────────────────── */

    @Override
    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(Long actorId, UUID workspaceId) {
        requireMembership(actorId, workspaceId);

        List<WorkspaceMember> members = memberRepository.findByWorkspaceId(workspaceId);
        Map<Long, UserSummary> byId = userDirectory
                .findByIds(members.stream().map(WorkspaceMember::getUserId).toList())
                .stream().collect(Collectors.toMap(UserSummary::id, u -> u));

        return members.stream()
                .map(m -> MemberResponse.from(m, byId.get(m.getUserId())))
                .sorted((a, b) -> a.joinedAt().compareTo(b.joinedAt()))
                .toList();
    }

    @Override
    @Transactional
    public MemberResponse invite(Long actorId, UUID workspaceId, String email) {
        requireRole(actorId, workspaceId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

        UserSummary user = userDirectory.findByEmail(email)
                .orElseThrow(() -> new MemberNotFoundException("No Rendra account for that email"));
        if (memberRepository.findByWorkspaceIdAndUserId(workspaceId, user.id()).isPresent()) {
            throw new AlreadyMemberException();
        }

        WorkspaceMember member = memberRepository.save(WorkspaceMember.builder()
                .workspaceId(workspaceId)
                .userId(user.id())
                .role(WorkspaceRole.MEMBER)
                .build());
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        notificationService.notify(user.id(), NotificationType.WORKSPACE_INVITE,
                "You were added to a workspace", workspace != null ? workspace.getName() : null, null, actorId);
        log.info("Member invited workspace={} user={} by={}", workspaceId, user.id(), actorId);
        return MemberResponse.from(member, user);
    }

    @Override
    @Transactional
    public MemberResponse updateMemberRole(Long actorId, UUID workspaceId, UUID memberId, WorkspaceRole role) {
        requireRole(actorId, workspaceId, WorkspaceRole.OWNER);
        if (role == WorkspaceRole.OWNER) throw new WorkspaceAccessDeniedException(); // OWNER only via transfer

        WorkspaceMember member = requireMemberInWorkspace(workspaceId, memberId);
        if (member.getRole() == WorkspaceRole.OWNER) throw new WorkspaceAccessDeniedException(); // don't demote owner
        member.setRole(role);

        UserSummary user = userDirectory.findByIds(List.of(member.getUserId())).stream().findFirst().orElse(null);
        log.info("Member role updated workspace={} member={} role={} by={}", workspaceId, memberId, role, actorId);
        return MemberResponse.from(member, user);
    }

    @Override
    @Transactional
    public void removeMember(Long actorId, UUID workspaceId, UUID memberId) {
        requireRole(actorId, workspaceId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

        WorkspaceMember member = requireMemberInWorkspace(workspaceId, memberId);
        if (member.getRole() == WorkspaceRole.OWNER) throw new WorkspaceAccessDeniedException(); // owner can't be removed
        memberRepository.delete(member);
        log.info("Member removed workspace={} member={} by={}", workspaceId, memberId, actorId);
    }

    /* ── Invite links ────────────────────────────────────────────────────────── */

    @Override
    @Transactional
    public WorkspaceInviteResponse createInvite(Long actorId, UUID workspaceId, String email, WorkspaceRole role) {
        requireRole(actorId, workspaceId, WorkspaceRole.OWNER, WorkspaceRole.ADMIN);
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow(WorkspaceNotFoundException::new);
        WorkspaceRole grant = (role == null || role == WorkspaceRole.OWNER) ? WorkspaceRole.MEMBER : role;

        WorkspaceInvite invite = inviteRepository.save(WorkspaceInvite.builder()
                .token(newToken())
                .workspaceId(workspaceId)
                .email(email != null && !email.isBlank() ? email.trim().toLowerCase() : null)
                .role(grant)
                .invitedByUserId(actorId)
                .expiresAt(Instant.now().plus(INVITE_TTL))
                .build());
        log.info("Invite created workspace={} by={} role={}", workspaceId, actorId, grant);
        return WorkspaceInviteResponse.of(invite, workspace.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(Long userId, String token) {
        WorkspaceInvite invite = inviteRepository.findByToken(token).orElse(null);
        if (invite == null) {
            return new InvitePreviewResponse(null, null, null, false, "This invite link is invalid.");
        }
        String reason = invalidReason(invite, userId);
        Workspace workspace = workspaceRepository.findById(invite.getWorkspaceId()).orElse(null);
        String inviter = userDirectory.findByIds(List.of(invite.getInvitedByUserId())).stream()
                .findFirst().map(u -> u.name() != null ? u.name() : u.email()).orElse("A teammate");
        return new InvitePreviewResponse(
                workspace != null ? workspace.getName() : null,
                inviter, invite.getRole(), reason == null, reason);
    }

    @Override
    @Transactional
    public void acceptInvite(Long userId, String token) {
        WorkspaceInvite invite = inviteRepository.findByToken(token)
                .orElseThrow(() -> new InviteInvalidException("This invite link is invalid."));
        String reason = invalidReason(invite, userId);
        if (reason != null) throw new InviteInvalidException(reason);

        if (memberRepository.findByWorkspaceIdAndUserId(invite.getWorkspaceId(), userId).isEmpty()) {
            memberRepository.save(WorkspaceMember.builder()
                    .workspaceId(invite.getWorkspaceId())
                    .userId(userId)
                    .role(invite.getRole())
                    .build());
        }
        invite.setStatus(InviteStatus.ACCEPTED);
        invite.setAcceptedByUserId(userId);
        log.info("Invite accepted workspace={} by={}", invite.getWorkspaceId(), userId);
    }

    /** Null when the invite can be accepted by this user; otherwise a human reason. */
    private String invalidReason(WorkspaceInvite invite, Long userId) {
        if (invite.getStatus() != InviteStatus.PENDING) return "This invite has already been used.";
        if (invite.getExpiresAt().isBefore(Instant.now())) return "This invite link has expired.";
        if (invite.getEmail() != null) {
            String myEmail = userDirectory.findByIds(List.of(userId)).stream()
                    .findFirst().map(u -> u.email() != null ? u.email().toLowerCase() : null).orElse(null);
            if (myEmail == null || !myEmail.equals(invite.getEmail())) {
                return "This invite was sent to a different email.";
            }
        }
        return null;
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Membership with one of the allowed roles, or 403. */
    private WorkspaceMember requireRole(Long userId, UUID workspaceId, WorkspaceRole... allowed) {
        WorkspaceMember member = requireMembership(userId, workspaceId);
        for (WorkspaceRole r : allowed) {
            if (member.getRole() == r) return member;
        }
        throw new WorkspaceAccessDeniedException();
    }

    /** Load a member handle scoped to the workspace, or 404. */
    private WorkspaceMember requireMemberInWorkspace(UUID workspaceId, UUID memberId) {
        WorkspaceMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberNotFoundException("Member not found"));
        if (!member.getWorkspaceId().equals(workspaceId)) {
            throw new MemberNotFoundException("Member not found");
        }
        return member;
    }

    /** Create a workspace and its OWNER membership together. */
    private Workspace provision(String name, Long ownerId, boolean personal) {
        Workspace workspace = workspaceRepository.save(Workspace.builder()
                .name(name)
                .ownerId(ownerId)
                .personal(personal)
                .build());
        memberRepository.save(WorkspaceMember.builder()
                .workspaceId(workspace.getId())
                .userId(ownerId)
                .role(WorkspaceRole.OWNER)
                .build());
        return workspace;
    }
}
