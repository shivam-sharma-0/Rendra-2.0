package com.code.pal.agent.web;

import com.code.pal.agent.dto.ProjectAnalytics;
import com.code.pal.agent.dto.SecurityScanResult;
import com.code.pal.agent.insight.ProjectInsightService;
import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Real project insights: activity analytics and a static security scan of the workspace. */
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
public class ProjectInsightsController {

    private final ProjectInsightService insights;

    @GetMapping("/analytics")
    public ProjectAnalytics analytics(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return insights.analytics(principal.userId(), projectId);
    }

    @GetMapping("/security-scan")
    public SecurityScanResult securityScan(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return insights.securityScan(principal.userId(), projectId);
    }
}
