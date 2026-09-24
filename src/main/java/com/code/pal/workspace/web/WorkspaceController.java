package com.code.pal.workspace.web;

import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.workspace.dto.CreateWorkspaceRequest;
import com.code.pal.workspace.dto.InviteMemberRequest;
import com.code.pal.workspace.dto.MemberResponse;
import com.code.pal.workspace.dto.TransferOwnershipRequest;
import com.code.pal.workspace.dto.UpdateMemberRoleRequest;
import com.code.pal.workspace.dto.UpdateWorkspaceRequest;
import com.code.pal.workspace.dto.WorkspaceResponse;
import com.code.pal.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Workspace endpoints. The caller is always the authenticated principal. */
@RestController
@RequestMapping("/api/workspaces")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    /** The caller's workspaces (personal one auto-created on first call). */
    @GetMapping
    public List<WorkspaceResponse> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return workspaceService.listForUser(principal.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(@AuthenticationPrincipal AuthPrincipal principal,
                                    @Valid @RequestBody CreateWorkspaceRequest request) {
        return workspaceService.create(principal.userId(), request);
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceResponse get(@AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID workspaceId) {
        return workspaceService.get(principal.userId(), workspaceId);
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceResponse rename(@AuthenticationPrincipal AuthPrincipal principal,
                                    @PathVariable UUID workspaceId,
                                    @Valid @RequestBody UpdateWorkspaceRequest request) {
        return workspaceService.rename(principal.userId(), workspaceId, request.name());
    }

    @PostMapping("/{workspaceId}/transfer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@AuthenticationPrincipal AuthPrincipal principal,
                         @PathVariable UUID workspaceId,
                         @Valid @RequestBody TransferOwnershipRequest request) {
        workspaceService.transferOwnership(principal.userId(), workspaceId, request.memberId());
    }

    @PostMapping("/{workspaceId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@AuthenticationPrincipal AuthPrincipal principal,
                      @PathVariable UUID workspaceId) {
        workspaceService.leave(principal.userId(), workspaceId);
    }

    @DeleteMapping("/{workspaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID workspaceId) {
        workspaceService.delete(principal.userId(), workspaceId);
    }

    /* ── Members ─────────────────────────────────────────────────────────────── */

    @GetMapping("/{workspaceId}/members")
    public List<MemberResponse> members(@AuthenticationPrincipal AuthPrincipal principal,
                                        @PathVariable UUID workspaceId) {
        return workspaceService.listMembers(principal.userId(), workspaceId);
    }

    @PostMapping("/{workspaceId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse invite(@AuthenticationPrincipal AuthPrincipal principal,
                                 @PathVariable UUID workspaceId,
                                 @Valid @RequestBody InviteMemberRequest request) {
        return workspaceService.invite(principal.userId(), workspaceId, request.email());
    }

    @PatchMapping("/{workspaceId}/members/{memberId}")
    public MemberResponse updateMemberRole(@AuthenticationPrincipal AuthPrincipal principal,
                                           @PathVariable UUID workspaceId,
                                           @PathVariable UUID memberId,
                                           @Valid @RequestBody UpdateMemberRoleRequest request) {
        return workspaceService.updateMemberRole(principal.userId(), workspaceId, memberId, request.role());
    }

    @DeleteMapping("/{workspaceId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AuthPrincipal principal,
                             @PathVariable UUID workspaceId,
                             @PathVariable UUID memberId) {
        workspaceService.removeMember(principal.userId(), workspaceId, memberId);
    }
}
