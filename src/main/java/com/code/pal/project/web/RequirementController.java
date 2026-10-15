package com.code.pal.project.web;

import com.code.pal.project.dto.RequirementDtos;
import com.code.pal.project.service.RequirementService;
import com.code.pal.shared.security.AuthPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/requirements")
@RequiredArgsConstructor
public class RequirementController {
    private final RequirementService requirements;

    @GetMapping
    public RequirementDtos.Session get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return requirements.get(principal.userId(), projectId);
    }

    @PostMapping("/start")
    public RequirementDtos.Session start(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return requirements.start(principal.userId(), projectId);
    }

    @PutMapping("/questions/{questionId}")
    public RequirementDtos.Session answer(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                          @PathVariable UUID questionId, @Valid @RequestBody RequirementDtos.AnswerRequest request) {
        return requirements.answer(principal.userId(), projectId, questionId, request);
    }

    @PostMapping("/approve")
    public RequirementDtos.Session approve(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                          @Valid @RequestBody RequirementDtos.ApprovalRequest request) {
        return requirements.approve(principal.userId(), projectId, request.revision());
    }
}
