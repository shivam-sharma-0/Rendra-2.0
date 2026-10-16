package com.code.pal.agent.change;

import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.capability.BuildDecisions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SavedBuildCaptureTest {
    @Test
    void anExistingImmutableSnapshotCannotBeOverwrittenByARepeatedCapture() {
        var project = LocalProject.create().write("index.html", "new");
        UUID run = UUID.randomUUID();
        project.write(SavedBuildStore.root(run) + "/index.html", "original snapshot");
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(run))).thenReturn(true);
        var store = new SavedBuildStore(jdbc, mock(PlatformTransactionManager.class), JsonMapper.builder().build(),
                project.files, mock(BuildDecisions.class), mock(ProjectLeaseService.class), project.sandbox);
        try (var ignored = RunFence.bind(new RunFence(project.projectId, run, UUID.randomUUID(), 1))) {
            assertThrows(RuntimeException.class, () -> store.capture(run, project.projectId));
        }
        assertEquals("original snapshot", project.read(SavedBuildStore.root(run) + "/index.html"));
    }

    @Test
    void aSnapshotCannotBeCapturedWithoutTheMatchingLiveCommitFence() {
        var project = LocalProject.create().write("index.html", "saved");
        var jdbc = mock(JdbcTemplate.class);
        var store = new SavedBuildStore(jdbc, mock(PlatformTransactionManager.class), JsonMapper.builder().build(),
                project.files, mock(BuildDecisions.class), mock(ProjectLeaseService.class), project.sandbox);
        assertThrows(RuntimeException.class, () -> store.capture(UUID.randomUUID(), project.projectId));
        verifyNoInteractions(jdbc);
    }

    @Test
    void capturesFullAfterCodeOnlyAndVerifiesItBeforePersistingEvidence() {
        var project = LocalProject.create().write("index.html", "saved").write(".env", "must not copy");
        byte[] binary = {0, 1, -1, 12, 0};
        project.files.writeBytes(project.projectId, "asset.bin", binary);
        var jdbc = mock(JdbcTemplate.class);
        var decisions = mock(BuildDecisions.class);
        var choice = new BuildDecisions.Choice(1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null);
        when(decisions.state(project.projectId)).thenReturn(new BuildDecisions.State(
                choice, new BuildDecisions.Available(false, null), null));
        UUID run = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            assertEquals("saved", project.read(SavedBuildStore.root(run) + "/index.html"));
            assertArrayEquals(binary, project.files.readBytes(project.projectId, SavedBuildStore.root(run), "asset.bin", 64));
            assertFalse(project.exists(SavedBuildStore.root(run) + "/.env"));
            return 1;
        });
        var store = new SavedBuildStore(jdbc, mock(PlatformTransactionManager.class), JsonMapper.builder().build(),
                project.files, decisions, mock(ProjectLeaseService.class), project.sandbox);
        var fence = new RunFence(project.projectId, run, UUID.randomUUID(), 1);
        try (var ignored = RunFence.bind(fence)) {
            store.capture(run, project.projectId);
        }
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
