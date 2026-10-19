package com.code.pal.agent.insight;

import com.code.pal.project.service.ProjectService;
import com.code.pal.workspace.exception.WorkspaceAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ModelUsageInsightsTest {
    @Test
    void inaccessibleProjectsNeverReachTheUsageLedger() {
        var rights = mock(ProjectService.class);
        var database = mock(JdbcTemplate.class);
        var project = UUID.randomUUID();
        when(rights.requireAccessible(7L, project)).thenThrow(new WorkspaceAccessDeniedException());
        assertThrows(WorkspaceAccessDeniedException.class,
                () -> new ModelUsageInsights(rights, database).forProject(7L, project));
        verifyNoInteractions(database);
    }
}
