package com.code.pal.agent.web;

import com.code.pal.identity.security.JwtService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Mints a short-lived, iframe-safe preview URL after the normal project access check. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/preview-token")
public class ProjectPreviewTokenController {
    private final ProjectService projects;
    private final JwtService jwt;

    @GetMapping
    public PreviewTokenResponse token(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        return new PreviewTokenResponse(jwt.generatePreviewToken(projectId));
    }

    public record PreviewTokenResponse(String token) {}
}
