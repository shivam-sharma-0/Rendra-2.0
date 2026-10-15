package com.code.pal.agent;

import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.realtime.RealtimeService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRunRecoveryTest {
    private final AgentRunRepository runs=mock(AgentRunRepository.class);
    private final SandboxPort sandbox=mock(SandboxPort.class);
    private final SiteBuildService sites=mock(SiteBuildService.class);
    private final ChangeSetService changes=mock(ChangeSetService.class);
    private final AgentRunRecovery recovery=new AgentRunRecovery(new AgentProperties(),runs,mock(AgentJobQueue.class),
            mock(AgentEvents.class),mock(MessageRepository.class),mock(RealtimeService.class),sandbox,changes,sites);
    private AgentRun abandoned(boolean saved) {
        var id=UUID.randomUUID();
        var run=AgentRun.builder().id(id).projectId(UUID.randomUUID()).userId(7L).messageId("fixture")
                .status(RunStatus.COMMITTING).snapshotRef("archive:"+id).codeSavedAt(saved ? Instant.now() : null).build();
        when(runs.findByStatusAndHeartbeatAtBefore(eq(RunStatus.COMMITTING),any())).thenReturn(List.of(run));return run;
    }
    @Test void uncertainRecoveryKeepsCommittingAndDoesNotClaimRestoration() {
        var run=abandoned(false);when(changes.recoverPromotion(run)).thenThrow(new com.code.pal.agent.change.PromotionRecoveryPendingException(null));
        recovery.sweep();assertEquals(RunStatus.COMMITTING,run.getStatus());verify(runs).flagSaveRecovery(run.getId());
        verify(runs,never()).save(any());verify(sandbox,never()).restoreTree(any(),any());verifyNoInteractions(sites);
    }
    @Test void savedCodeSurvivesAnInterruptedPreview() {
        var run=abandoned(true);recovery.sweep();
        verify(sandbox,never()).restoreTree(any(),any());assertNotNull(run.getCodeSavedAt());
        assertEquals(RunStatus.FAILED,run.getStatus());assertEquals("FAILED",run.getPreviewStatus());
    }
    @Test void verifiedRecoveryUsesTheJournalAndDoesNotRestoreTheWholeTree() {
        var run=abandoned(false);when(changes.recoverPromotion(run)).thenReturn(true);recovery.sweep();
        verify(sandbox,never()).restoreTree(any(),any());
        verify(sites).buildLater(run.getProjectId());assertNull(run.getCodeSavedAt());assertEquals(RunStatus.FAILED,run.getStatus());
    }
}
