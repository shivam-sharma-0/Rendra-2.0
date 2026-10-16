package com.code.pal.project.service;

import com.code.pal.project.domain.ProjectEnvironmentVariable;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectBuildVariablesTest {
    private final UUID projectId = UUID.randomUUID();
    private final ProjectEnvironmentVariableRepository repository = mock(ProjectEnvironmentVariableRepository.class);
    private final ProjectBuildVariables variables = new ProjectBuildVariables(repository);

    private void stored(String name, String value) {
        when(repository.findByProjectIdOrderByNameAsc(projectId)).thenReturn(List.of(
                ProjectEnvironmentVariable.builder().projectId(projectId).name(name).value(value).build()));
    }

    @Test
    void aSecretKeyStoredBeforeTheCheckFailsTheBuildWithoutBeingEchoed() {
        stored("VITE_SUPABASE_ANON_KEY", "sb_secret_legacy123");
        var error = assertThrows(IllegalStateException.class, () -> variables.variables(projectId));
        assertFalse(error.getMessage().contains("sb_secret_legacy123"));
    }

    @Test
    void publicValuesPassThrough() {
        stored("VITE_SUPABASE_ANON_KEY", "sb_publishable_abc");
        assertEquals(Map.of("VITE_SUPABASE_ANON_KEY", "sb_publishable_abc"), variables.variables(projectId));
    }
}
