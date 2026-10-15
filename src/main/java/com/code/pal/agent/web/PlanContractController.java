package com.code.pal.agent.web;

import com.code.pal.agent.contract.ContractStore;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.exception.WorkflowStageException;

@RestController
@RequestMapping("/api/projects/{projectId}/agent-runs/{runId}/plan")
public final class PlanContractController {
    private final ContractStore contracts;
    private final ProjectService projects;
    private final AgentProperties props;

    public PlanContractController(ContractStore contracts, ProjectService projects, AgentProperties props) {
        this.contracts = contracts;
        this.projects = projects;
        this.props = props;
    }

    public record Approval(UUID contractId, String reviewedHash) {
    }

    @GetMapping
    public ContractStore.Review view(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        projects.requireAccessible(principal.userId(), projectId);
        return props.isStructuredContracts() ? contracts.view(projectId, runId) : null;
    }

    @PostMapping("/approve")
    public ContractStore.Review approve(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
            @PathVariable UUID runId, @RequestBody Approval approval) {
        projects.requireCanEdit(principal.userId(), projectId);
        if (!props.isStructuredContracts())
            throw new WorkflowStageException("Reusable plans are unavailable");
        if (approval.contractId() == null || approval.reviewedHash() == null)
            throw new WorkflowStageException("Review the plan before approving");
        return contracts.approve(principal.userId(), projectId, runId, approval.contractId(), approval.reviewedHash());
    }
}
