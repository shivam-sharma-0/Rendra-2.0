package com.code.pal.project.requirement;

import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.ProjectPaths;
import com.code.pal.project.brief.ProjectBriefFile;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectStage;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.domain.RequirementSession;
import com.code.pal.project.domain.RequirementStatus;
import com.code.pal.project.exception.RequirementApprovalRequiredException;
import com.code.pal.project.repository.RequirementSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** The first actionable request after a greeting enters requirements in the message transaction. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BuildRequestIntake {
    private final RequirementCoordinator coordinator;
    private final RequirementSessionRepository sessions;
    private final ProjectFiles files;

    public BuildRequestIntake(RequirementCoordinator coordinator, RequirementSessionRepository sessions, ProjectFiles files) {
        this.coordinator = coordinator;
        this.sessions = sessions;
        this.files = files;
    }

    public boolean prepare(Project project, ProjectType mode, String prompt) {
        if (mode != ProjectType.BUILD || project.getType() != ProjectType.BUILD || project.getLifecycleStage() != ProjectStage.DESIGN) return false;
        coordinator.lock(project.getId());
        var current = sessions.findByProjectId(project.getId());
        if (current.isPresent()) {
            if (current.get().getStatus() != RequirementStatus.APPROVED) throw new RequirementApprovalRequiredException();
            return false;
        }
        var entries = files.list(project.getId(), ".", 1, Set.of(ProjectPaths.INTERNAL, "uploads", ".git"));
        if (entries.stream().anyMatch(entry -> !entry.path().equals(ProjectBriefFile.NAME))) return false;
        coordinator.requireNoActiveBuild(project.getId());
        // The session is the durable first-request claim, so a concurrent request cannot replace this prompt.
        sessions.saveAndFlush(RequirementSession.builder().projectId(project.getId()).sourcePrompt(prompt).build());
        project.setInitialPrompt(prompt);
        project.setLifecycleStage(ProjectStage.REQUIREMENTS);
        return true;
    }
}
