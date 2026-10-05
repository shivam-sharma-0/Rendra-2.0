package com.code.pal.agent.web;

import com.code.pal.agent.preview.DraftPreviewService;
import com.code.pal.agent.preview.PreviewService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.identity.security.JwtService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.shared.web.SitesOrigin;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Mints a short-lived preview URL after the normal project access check. The URL is on the
 * separate sites origin, so the previewed (untrusted) app can never reach the API with the
 * viewer's cookies.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/preview-token")
public class ProjectPreviewTokenController {
    private final ProjectService projects;
    private final JwtService jwt;
    private final SitesOrigin sites;
    private final PreviewService preview;
    private final SiteBuildService builds;
    private final DraftPreviewService drafts;

    @GetMapping
    public PreviewTokenResponse token(@AuthenticationPrincipal AuthPrincipal principal,
                                      @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        String token = jwt.generatePreviewToken(projectId);
        DraftPreviewService.ActiveDraft active = drafts.activeDraft(projectId);
        Draft draft = null;
        if (active != null) {
            String draftToken = jwt.generatePreviewToken(projectId, active.runId());
            draft = new Draft(active.runId(), active.version(), sites.previewUrl(draftToken));
        }
        return new PreviewTokenResponse(token, sites.previewUrl(token), preview.available(projectId), builds.status(projectId), draft);
    }

    /**
     * {@code available}: there is something to show (a built site, or a static site's own files).
     * {@code draft}: the running build's unvalidated changes, while there are some to show.
     */
    public record PreviewTokenResponse(String token, String url, boolean available, SiteBuildService.PreviewStatus status, Draft draft) {}

    public record Draft(UUID runId, String version, String url) {}
}
