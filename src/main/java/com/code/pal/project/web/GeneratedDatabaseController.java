package com.code.pal.project.web;

import com.code.pal.project.database.GeneratedDatabaseService;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/database")
@RequiredArgsConstructor
public class GeneratedDatabaseController {
    private final ProjectService projects;
    private final GeneratedDatabaseService databases;

    @GetMapping
    public GeneratedDatabaseService.DatabaseSnapshot get(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @PathVariable UUID projectId) {
        projects.requireAccessible(principal.userId(), projectId);
        return databases.inspect(projectId);
    }
}
