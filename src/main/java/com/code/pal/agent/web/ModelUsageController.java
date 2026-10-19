package com.code.pal.agent.web;

import com.code.pal.agent.insight.ModelUsageInsights;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/model-usage")
public class ModelUsageController {
    private final ModelUsageInsights insights;

    @GetMapping
    public ModelUsageInsights.Summary usage(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return insights.forProject(principal.userId(), projectId);
    }
}
