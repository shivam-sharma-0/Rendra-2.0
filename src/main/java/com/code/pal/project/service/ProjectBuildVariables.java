package com.code.pal.project.service;

import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The stored variables as builds see them. No authorization: builds run for a project the caller
 * already acts on. Kept apart from {@link ProjectEnvironmentService} so the build side does not depend
 * on project access checks (which in turn depend on publishing, which builds).
 */
@Component
@RequiredArgsConstructor
public class ProjectBuildVariables implements BuildVariables {
    private final ProjectEnvironmentVariableRepository variables;

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> variables(UUID projectId) {
        Map<String, String> result = new LinkedHashMap<>();
        variables.findByProjectIdOrderByNameAsc(projectId).forEach(variable -> result.put(variable.getName(), variable.getValue()));
        return result;
    }
}
