package com.code.pal.workspace.web;

import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.workspace.dto.CreateInviteRequest;
import com.code.pal.workspace.dto.InvitePreviewResponse;
import com.code.pal.workspace.dto.WorkspaceInviteResponse;
import com.code.pal.workspace.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Shareable workspace invite links: create (workspace-scoped), then preview/accept by token.
 * All endpoints require a signed-in caller (accepting always joins the authenticated account).
 */
@RestController
@RequiredArgsConstructor
public class InviteController {

    private final WorkspaceService workspaceService;

    @PostMapping("/api/workspaces/{workspaceId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceInviteResponse create(@AuthenticationPrincipal AuthPrincipal principal,
                                          @PathVariable UUID workspaceId,
                                          @Valid @RequestBody(required = false) CreateInviteRequest request) {
        String email = request != null ? request.email() : null;
        var role = request != null ? request.role() : null;
        return workspaceService.createInvite(principal.userId(), workspaceId, email, role);
    }

    @GetMapping("/api/invites/{token}")
    public InvitePreviewResponse preview(@AuthenticationPrincipal AuthPrincipal principal,
                                         @PathVariable String token) {
        return workspaceService.previewInvite(principal.userId(), token);
    }

    @PostMapping("/api/invites/{token}/accept")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String token) {
        workspaceService.acceptInvite(principal.userId(), token);
    }
}
