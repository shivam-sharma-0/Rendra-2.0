package com.code.pal.agent.web;

import com.code.pal.agent.change.SavedBuildRestoreService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.shared.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Restore any recent saved build's code. Review first (POST, so the digest is never cached), then restore with
 * that digest and a client-kept operation id, so a retried request after a lost response cannot restore twice.
 * Restoring never publishes; the published site changes only on an explicit publish.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class SavedBuildController {
    private static final String RESTORED = "RESTORED";

    private final SavedBuildRestoreService restores;
    private final SiteBuildService sites;

    public SavedBuildController(SavedBuildRestoreService restores, SiteBuildService sites) {
        this.restores = restores;
        this.sites = sites;
    }

    @GetMapping("/saved-builds")
    public SavedBuildRestoreService.History history(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID projectId) {
        return restores.history(user.userId(), projectId);
    }

    @PostMapping("/saved-builds/{runId}/review")
    public SavedBuildRestoreService.Review review(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID projectId,
                                                  @PathVariable UUID runId) {
        return restores.review(user.userId(), projectId, runId);
    }

    @PostMapping("/saved-builds/{runId}/restore")
    public SavedBuildRestoreService.Result restore(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID projectId,
                                                   @PathVariable UUID runId, @RequestBody RestoreRequest request) {
        var result = restores.restore(user.userId(), projectId, runId, request.operationId(), request.expectedDigest());
        if (RESTORED.equals(result.state())) {
            sites.buildLater(projectId);   // the preview follows the restored code; outside the mutation lock
        }
        return result;
    }

    @GetMapping("/saved-build-restores/{operationId}")
    public SavedBuildRestoreService.Result operation(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID projectId,
                                                     @PathVariable UUID operationId) {
        return restores.operation(user.userId(), projectId, operationId);
    }

    public record RestoreRequest(UUID operationId, String expectedDigest) {}
}
