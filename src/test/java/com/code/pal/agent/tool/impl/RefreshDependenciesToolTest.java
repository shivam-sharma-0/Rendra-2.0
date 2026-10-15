package com.code.pal.agent.tool.impl;

import com.code.pal.agent.change.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.stack.DependencyService;
import com.code.pal.agent.tool.*;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RefreshDependenciesToolTest {
    private final LocalProject project = LocalProject.create();
    private final ChangeSetService changes = new ChangeSetService(mock(FileChangeRepository.class), mock(AgentRunRepository.class),
            new ObjectMapper(), project.properties, project.files, project.sandbox, mock(ProjectService.class));
    private final DependencyService deps = mock(DependencyService.class);
    private final RefreshDependenciesTool tool = new RefreshDependenciesTool(changes, deps);
    private final String manifest = "{\"dependencies\":{\"react\":\"19.3.0\"}}";
    private final String lock = "{\"lockfileVersion\":3,\"packages\":{\"\":{\"dependencies\":{\"react\":\"19.3.0\"}}}}";
    private AgentContext tools() {
        UUID run = UUID.randomUUID(); var plan = new AgentPlanState(); plan.revise(List.of("Dependencies", "Validate"), "Initial");
        return new AgentContext(project.projectId, 7L, ProjectType.BUILD, changes.start(run, project.projectId), null,
                new com.code.pal.agent.context.ProjectContextTrace(), run, plan, project.files);
    }
    @Test void staleLockBlocksValidationUntilResolvedLockIsRecordedInStage() {
        project.write("package.json", manifest).write("package-lock.json", "{\"packages\":{\"\":{}}}");
        var context = tools();
        changes.patch(context, "package.json", project.files.version(project.projectId, context.root(), "package.json"), manifest, manifest + "\n");
        assertTrue(changes.validate(context).issues().stream().anyMatch(issue -> issue.code().equals("DEPENDENCY_LOCK_STALE")));
        when(deps.refreshNpmLocks(project.projectId, context.root())).thenReturn(Map.of("package-lock.json", lock));
        tool.execute(Map.of(), context);
        assertTrue(changes.validate(context).valid());
        assertTrue(changes.view(context).touchedPaths().contains("package-lock.json"));
        assertEquals("{\"packages\":{\"\":{}}}", project.read("package-lock.json"));
        int revision = changes.view(context).revision();
        tool.execute(Map.of(), context);
        assertEquals(revision, changes.view(context).revision());
    }
    @Test void installFailureDoesNotTouchExistingLockOrRevision() {
        project.write("package.json", manifest).write("package-lock.json", lock);
        var context = tools();
        when(deps.refreshNpmLocks(project.projectId, context.root())).thenThrow(new IllegalStateException("Injected install failure"));
        assertEquals("DEPENDENCY_REFRESH_FAILED", assertThrows(AgentToolException.class, () -> tool.execute(Map.of(), context)).code());
        assertEquals(0, changes.view(context).revision());
        assertEquals(lock, project.files.readText(project.projectId, context.root(), "package-lock.json"));
    }
    @Test void deletingARequiredTouchedLockCannotPassValidation() {
        project.write("package.json", manifest).write("package-lock.json", lock);
        var context = tools();
        changes.delete(context, "package-lock.json", project.files.version(project.projectId, context.root(), "package-lock.json"));
        assertFalse(changes.validate(context).valid());
        when(deps.refreshNpmLocks(project.projectId, context.root())).thenReturn(Map.of("package-lock.json", lock));
        tool.execute(Map.of(), context);
        assertTrue(changes.validate(context).valid());
    }
    @Test void emptyOptionalSectionsMatchNpmOmission() {
        var mapper = new ObjectMapper();
        assertTrue(com.code.pal.agent.stack.NpmLockfiles.matches(mapper.readTree("{\"optionalDependencies\":{}}"), mapper.readTree("{}")));
        assertFalse(com.code.pal.agent.stack.NpmLockfiles.matches(mapper.readTree(manifest), mapper.readTree("{}")));
    }
    @Test void anUnusedNpmLockDoesNotBlockAPnpmProject() {
        String declared = "{\"packageManager\":\"pnpm@9.12.0\",\"dependencies\":{\"react\":\"19.3.0\"}}";
        project.write("package.json", declared).write("package-lock.json", "{\"packages\":{\"\":{}}}");
        var context = tools();
        changes.patch(context, "package.json", project.files.version(project.projectId, context.root(), "package.json"), declared, declared + "\n");
        assertTrue(changes.validate(context).valid());
    }
}
