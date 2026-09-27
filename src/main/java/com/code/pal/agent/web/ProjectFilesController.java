package com.code.pal.agent.web;

import com.code.pal.agent.dto.FileContentResponse;
import com.code.pal.agent.dto.FileNode;
import com.code.pal.agent.workspace.WorkspaceBrowser;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Read-only view of a project's agent workspace files, powering the code/files panel. */
@RestController
@RequestMapping("/api/projects/{projectId}/files")
@RequiredArgsConstructor
public class ProjectFilesController {

    private final ProjectService projects;
    private final WorkspaceBrowser browser;

    @GetMapping
    public List<FileNode> tree(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        return browser.tree(projectId);
    }

    @GetMapping("/content")
    public FileContentResponse content(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable UUID projectId,
                                       @RequestParam String path) {
        projects.requireAccessible(principal.userId(), projectId);
        try {
            return browser.read(projectId, path);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
