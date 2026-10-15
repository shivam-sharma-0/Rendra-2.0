package com.code.pal.agent.web;

import com.code.pal.agent.change.*;
import com.code.pal.agent.dto.AgentRunResponse;
import com.code.pal.agent.exception.AgentRunNotFoundException;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Authenticated source inspection/export only; no public preview or publish capability. */
@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs/{runId}/failed-draft")
public class FailedDraftController {
    private final ProjectService projects;
    private final AgentRunRepository runs;
    private final FailedDraftService drafts;
    private final FailedDraftRetryService retries;
    public FailedDraftController(ProjectService projects,AgentRunRepository runs,FailedDraftService drafts,FailedDraftRetryService retries) {
        this.projects=projects;this.runs=runs;this.drafts=drafts;this.retries=retries;
    }
    @GetMapping
    public FailedDraftService.Info info(@AuthenticationPrincipal AuthPrincipal user,@PathVariable UUID projectId,@PathVariable UUID runId) {
        authorize(user,projectId,runId);return drafts.info(projectId,runId);
    }
    @GetMapping("/file")
    public FailedDraftService.FileView file(@AuthenticationPrincipal AuthPrincipal user,@PathVariable UUID projectId,@PathVariable UUID runId,@RequestParam String path) {
        authorize(user,projectId,runId);return drafts.file(projectId,runId,path);
    }
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthPrincipal user,@PathVariable UUID projectId,@PathVariable UUID runId) {
        authorize(user,projectId,runId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"unverified-build-"+runId+".zip\"")
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(drafts.download(projectId,runId));
    }
    @PostMapping("/retry")
    public AgentRunResponse retry(@AuthenticationPrincipal AuthPrincipal user,@PathVariable UUID projectId,@PathVariable UUID runId,
                                  @Valid @RequestBody FailedDraftRetryService.Request request) {
        return AgentRunResponse.from(retries.retry(user.userId(),projectId,runId,request));
    }
    private void authorize(AuthPrincipal user,UUID project,UUID run) {
        projects.requireAccessible(user.userId(),project);
        runs.findByIdAndProjectId(run,project).orElseThrow(()->new AgentRunNotFoundException("Agent run not found"));
    }
}
