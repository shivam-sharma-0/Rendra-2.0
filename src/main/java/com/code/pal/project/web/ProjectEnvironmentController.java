package com.code.pal.project.web;

import com.code.pal.project.service.ProjectEnvironmentService;
import com.code.pal.shared.security.AuthPrincipal;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Build-time variables of a project's site (VITE_*). Changing one rebuilds the preview; the published
 * site picks it up on the next publish.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/environment")
public class ProjectEnvironmentController {
    private final ProjectEnvironmentService environment;

    @GetMapping
    public List<ProjectEnvironmentService.Variable> list(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return environment.list(principal.userId(), projectId);
    }

    @PutMapping("/{name}")
    public ProjectEnvironmentService.Variable set(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                                 @PathVariable String name, @RequestBody ValueRequest request) {
        return environment.set(principal.userId(), projectId, name, request.value());
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId, @PathVariable String name) {
        environment.delete(principal.userId(), projectId, name);
    }

    public record ValueRequest(@NotNull String value) {}
}
