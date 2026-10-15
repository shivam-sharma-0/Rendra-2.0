package com.code.pal.agent.web;

import com.code.pal.agent.preview.PublishedSiteService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
public final class PublicationCapabilityController {
    private final ProjectService projects;
    private final PublishedSiteService sites;

    public PublicationCapabilityController(ProjectService projects, PublishedSiteService sites) {
        this.projects = projects;
        this.sites = sites;
    }

    @GetMapping("/api/projects/{projectId}/publish-capability")
    public PublishedSiteService.Capability capability(@AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable UUID projectId) {
        projects.requireManageable(actor.userId(), projectId);
        return sites.capability(projectId);
    }
}
