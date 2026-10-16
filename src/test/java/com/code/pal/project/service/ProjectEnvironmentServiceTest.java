package com.code.pal.project.service;

import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.project.domain.ProjectEnvironmentVariable;
import com.code.pal.project.exception.InvalidEnvironmentVariableException;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectEnvironmentServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectEnvironmentVariableRepository repository = mock(ProjectEnvironmentVariableRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ProjectEnvironmentService service = new ProjectEnvironmentService(projects, repository, events);

    @Test
    void aValidVariableIsStoredAndTheSiteRebuilt() {
        when(repository.findByProjectIdAndName(projectId, "VITE_SUPABASE_URL")).thenReturn(Optional.empty());

        service.set(7L, projectId, "VITE_SUPABASE_URL", "https://abc.supabase.co");

        verify(projects).requireCanEdit(7L, projectId);
        verify(repository).save(any(ProjectEnvironmentVariable.class));
        verify(events).publishEvent(new BuildVariables.Changed(projectId));
    }

    @Test
    void namesAndValuesThatWouldBreakTheBuildFileAreRejected() {
        for (String name : new String[]{"API_URL", "vite_x", "VITE_", "VITE_A-B", "VITE_" + "X".repeat(60)}) {
            assertThrows(InvalidEnvironmentVariableException.class, () -> service.set(7L, projectId, name, "x"), name);
        }
        for (String value : new String[]{"it's", "line\nbreak", "tab\u0000", "x".repeat(4_001), "sb_secret_abc123"}) {
            assertThrows(InvalidEnvironmentVariableException.class, () -> service.set(7L, projectId, "VITE_OK", value), value);
        }
        verify(repository, never()).save(any());
    }

    @Test
    void aProjectHasABoundedNumberOfVariables() {
        when(repository.findByProjectIdAndName(projectId, "VITE_NEW")).thenReturn(Optional.empty());
        when(repository.countByProjectId(projectId)).thenReturn(50L);

        var error = assertThrows(InvalidEnvironmentVariableException.class, () -> service.set(7L, projectId, "VITE_NEW", "x"));
        assertEquals(400, error.status().value());
    }
}
