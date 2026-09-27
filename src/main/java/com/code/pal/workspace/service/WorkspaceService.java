package com.code.pal.workspace.service;

import com.code.pal.workspace.domain.WorkspaceMember;
import com.code.pal.workspace.domain.WorkspaceRole;
import com.code.pal.workspace.dto.CreateWorkspaceRequest;
import com.code.pal.workspace.dto.InvitePreviewResponse;
import com.code.pal.workspace.dto.MemberResponse;
import com.code.pal.workspace.dto.WorkspaceInviteResponse;
import com.code.pal.workspace.dto.WorkspaceResponse;

import java.util.List;
import java.util.UUID;

/**
 * Workspace use-cases and the shared access gate. Other modules depend on
 * {@link #requireMembership} to authorize workspace-scoped work.
 */
public interface WorkspaceService {

    /** The caller's private workspace, provisioning it (and an OWNER membership) on first use. */
    WorkspaceResponse getOrCreatePersonal(Long userId);

    /** Every workspace the caller belongs to (ensures the personal one exists first). */
    List<WorkspaceResponse> listForUser(Long userId);

    /** Create a shared (team) workspace owned by the caller. */
    WorkspaceResponse create(Long userId, CreateWorkspaceRequest request);

    /** A single workspace the caller belongs to (for the settings page). */
    WorkspaceResponse get(Long actorId, UUID workspaceId);

    /** Rename the workspace (caller must be OWNER/ADMIN). */
    WorkspaceResponse rename(Long actorId, UUID workspaceId, String name);

    /** Hand primary ownership to another member; the caller becomes ADMIN (caller must be OWNER). */
    void transferOwnership(Long actorId, UUID workspaceId, UUID newOwnerMemberId);

    /** Leave the workspace (the OWNER must transfer first). */
    void leave(Long actorId, UUID workspaceId);

    /** Soft-delete the workspace and revoke access (caller must be OWNER; personal can't be deleted). */
    void delete(Long actorId, UUID workspaceId);

    /**
     * Authorize the caller for a workspace, returning their membership (role included).
     *
     * @throws com.code.pal.workspace.exception.WorkspaceNotFoundException     if it doesn't exist
     * @throws com.code.pal.workspace.exception.WorkspaceAccessDeniedException if the caller isn't a member
     */
    WorkspaceMember requireMembership(Long userId, UUID workspaceId);

    /** Non-throwing membership check — used by other modules to combine with project-level access. */
    boolean isMember(Long userId, UUID workspaceId);

    /* ── Members (collaboration) ─────────────────────────────────────────────── */

    /** List the workspace's members (caller must be a member). */
    List<MemberResponse> listMembers(Long actorId, UUID workspaceId);

    /** Invite an existing account by email (caller must be OWNER/ADMIN). */
    MemberResponse invite(Long actorId, UUID workspaceId, String email);

    /** Change a member's role (caller must be OWNER). */
    MemberResponse updateMemberRole(Long actorId, UUID workspaceId, UUID memberId, WorkspaceRole role);

    /** Remove a member (caller must be OWNER/ADMIN; the OWNER can't be removed). */
    void removeMember(Long actorId, UUID workspaceId, UUID memberId);

    /* ── Invite links (accept flow) ──────────────────────────────────────────── */

    /** Create a shareable invite link (caller must be OWNER/ADMIN). */
    WorkspaceInviteResponse createInvite(Long actorId, UUID workspaceId, String email, WorkspaceRole role);

    /** Preview an invite for the accept screen (caller must be signed in). Never throws for a bad token. */
    InvitePreviewResponse previewInvite(Long userId, String token);

    /** Accept an invite, joining the workspace (caller must be signed in). */
    void acceptInvite(Long userId, String token);
}
