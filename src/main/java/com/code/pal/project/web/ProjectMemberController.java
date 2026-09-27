package com.code.pal.project.web;

import com.code.pal.project.dto.ProjectMemberResponse;
import com.code.pal.project.dto.ShareProjectRequest;
import com.code.pal.project.dto.UpdateProjectMemberRoleRequest;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Per-project sharing: who a project is shared with, and at what role. */
@RestController
@RequestMapping("/api/projects/{projectId}/members")
@RequiredArgsConstructor
public class ProjectMemberController {

    private final ProjectService projectService;

    @GetMapping
    public List<ProjectMemberResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID projectId) {
        return projectService.listMembers(principal.userId(), projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectMemberResponse share(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID projectId,
                                       @Valid @RequestBody ShareProjectRequest request) {
        return projectService.share(principal.userId(), projectId, request.email(), request.role());
    }

    @PatchMapping("/{memberId}")
    public ProjectMemberResponse updateRole(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable UUID projectId,
                                            @PathVariable UUID memberId,
                                            @Valid @RequestBody UpdateProjectMemberRoleRequest request) {
        return projectService.updateMemberRole(principal.userId(), projectId, memberId, request.role());
    }

    @DeleteMapping("/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthPrincipal principal,
                       @PathVariable UUID projectId,
                       @PathVariable UUID memberId) {
        projectService.removeMember(principal.userId(), projectId, memberId);
    }
}
