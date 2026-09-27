package com.code.pal.project.web;

import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.dto.CreateProjectRequest;
import com.code.pal.project.dto.ProjectResponse;
import com.code.pal.project.dto.UpdateProjectRequest;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.shared.web.ApiConstants;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project CRUD. Create/list are nested under a workspace (the scope);
 * single-project routes take
 * the project id and authorize via its workspace membership. The owner is
 * always the verified
 * token, never the body. Everything is behind
 * {@code anyRequest().authenticated()}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping("/workspaces/{workspaceId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID workspaceId,
            @Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(principal.userId(), workspaceId, request);
    }

    /**
     * A workspace's projects, most-recently-active first. Sort is fixed server-side
     * so the query
     * always rides the {@code (workspace_id, last_activity_at)} index.
     */
    @GetMapping("/workspaces/{workspaceId}/projects")
    public Page<ProjectResponse> list(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID workspaceId,
            @RequestParam(name = "type", required = false) List<ProjectType> types,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), ApiConstants.MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "lastActivityAt"));
        return projectService.list(principal.userId(), workspaceId, types, pageable);
    }

    @GetMapping("/projects/{id}")
    public ProjectResponse get(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        return projectService.get(principal.userId(), id);
    }

    @PatchMapping("/projects/{id}")
    public ProjectResponse update(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProjectRequest request) {
        return projectService.update(principal.userId(), id, request);
    }

    @DeleteMapping("/projects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID id) {
        projectService.delete(principal.userId(), id);
    }

    /** Publish the project — makes it LIVE at a public URL. */
    @PostMapping("/projects/{id}/publish")
    public ProjectResponse publish(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return projectService.publish(principal.userId(), id);
    }

    /** Take a published project offline. */
    @PostMapping("/projects/{id}/unpublish")
    public ProjectResponse unpublish(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID id) {
        return projectService.unpublish(principal.userId(), id);
    }
}
