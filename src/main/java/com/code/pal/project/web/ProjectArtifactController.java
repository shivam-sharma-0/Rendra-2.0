package com.code.pal.project.web;

import com.code.pal.project.artifact.ProjectArtifactService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/artifacts")
@RequiredArgsConstructor
public class ProjectArtifactController {
    private final ProjectService projects;
    private final ProjectArtifactService artifacts;

    @GetMapping
    public List<ProjectArtifactService.Artifact> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                       @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        return artifacts.list(projectId);
    }

    @GetMapping("/content")
    public String content(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                          @RequestParam String path) {
        projects.requireAccessible(principal.userId(), projectId);
        return artifacts.read(projectId, path);
    }
}
