package com.code.pal.agent.change;

import com.code.pal.agent.AgentRunCreator;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.exception.*;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailedDraftRetryServiceTest {
    final UUID project=UUID.randomUUID(),sourceId=UUID.randomUUID();
    final ProjectService projects=mock(ProjectService.class);
    final AgentRunRepository runs=mock(AgentRunRepository.class);
    final MessageRepository messages=mock(MessageRepository.class);
    final MessageService turns=mock(MessageService.class);
    final AgentRunCreator creator=mock(AgentRunCreator.class);
    final FailedDraftRepository repository=mock(FailedDraftRepository.class);
    final FailedDraftService drafts=mock(FailedDraftService.class);
    final FailedDraftRetryService service=new FailedDraftRetryService(projects,runs,messages,turns,creator,repository,drafts);
    final FailedDraftRetryService.Request request=new FailedDraftRetryService.Request("umsg_retry","aimsg_retry");
    final AgentRun source=AgentRun.builder().id(sourceId).projectId(project).userId(7L).mode(ProjectType.BUILD).status(RunStatus.FAILED).requestMessageId("umsg_original").build();
    final AgentRun retry=AgentRun.builder().id(UUID.randomUUID()).projectId(project).userId(7L).mode(ProjectType.BUILD).status(RunStatus.QUEUED).requestMessageId(request.id()).messageId(request.aiMessageId()).build();
    FailedDraftRetryServiceTest() {
        when(runs.findByIdAndProjectId(sourceId,project)).thenReturn(Optional.of(source));
        when(messages.findByIdAndProjectId(source.getRequestMessageId(),project)).thenReturn(Optional.of(Message.user(project,"umsg_original","Build a todo app",ProjectType.BUILD,7L)));
        when(creator.create(any())).thenReturn(retry);when(runs.bindRetrySource(retry.getId(),project,sourceId)).thenReturn(1);
    }
    @Test void acceptsNewBoundRunThroughNormalMessageAdmissionWithoutMutatingFailedRun() {
        assertSame(retry,service.retry(7L,project,sourceId,request));
        assertEquals(sourceId,retry.getRetrySourceRunId());assertEquals(RunStatus.FAILED,source.getStatus());
        var accepted=ArgumentCaptor.forClass(CreateMessageRequest.class);verify(turns).post(eq(7L),eq(project),accepted.capture());
        assertEquals(ProjectType.BUILD,accepted.getValue().mode());assertTrue(accepted.getValue().message().contains("Build a todo app"));
        verify(drafts).retryManifest(project,sourceId);verify(repository).markRetry(project,sourceId,retry.getId());
    }
    @Test void identicalRetryIsIdempotentEvenAfterArtifactExpiration() {
        retry.setRetrySourceRunId(sourceId);
        when(repository.find(project,sourceId)).thenReturn(new FailedDraftRepository.Stored(sourceId,project,"{}",null,null,"EXPIRED",retry.getId()));
        when(runs.findByIdAndProjectId(retry.getId(),project)).thenReturn(Optional.of(retry));
        assertSame(retry,service.retry(7L,project,sourceId,request));
        verifyNoInteractions(turns,creator,drafts);verify(runs,never()).bindRetrySource(any(),any(),any());
        assertThrows(AgentRunConflictException.class,()->service.retry(7L,project,sourceId,new FailedDraftRetryService.Request("umsg_other","aimsg_other")));
    }
    @Test void requiresCurrentEditAccessBeforeArchiveOrMessageReads() {
        doThrow(new com.code.pal.workspace.exception.WorkspaceAccessDeniedException()).when(projects).requireCanEdit(7L,project);
        assertThrows(com.code.pal.workspace.exception.WorkspaceAccessDeniedException.class,()->service.retry(7L,project,sourceId,request));
        verifyNoInteractions(runs,repository,drafts,messages,turns,creator);
    }
    @Test void refusesForeignSavedOrChangedBasisBeforeAnyNewTurn() {
        assertThrows(AgentRunNotFoundException.class,()->service.retry(7L,UUID.randomUUID(),sourceId,request));
        source.setCodeSavedAt(java.time.Instant.now());assertThrows(AgentRunConflictException.class,()->service.retry(7L,project,sourceId,request));
        source.setCodeSavedAt(null);doThrow(new AgentRunConflictException("Basis changed")).when(drafts).retryManifest(project,sourceId);
        assertThrows(AgentRunConflictException.class,()->service.retry(7L,project,sourceId,request));verifyNoInteractions(turns,creator);
    }
    @Test void activeProjectAndLateBindingRefusalDoNotPretendRetryAccepted() {
        when(runs.findByProjectIdAndStatusIn(eq(project),anyList())).thenReturn(List.of(retry));
        assertThrows(AgentRunConflictException.class,()->service.retry(7L,project,sourceId,request));verifyNoInteractions(turns);
        when(runs.findByProjectIdAndStatusIn(eq(project),anyList())).thenReturn(List.of());when(runs.bindRetrySource(retry.getId(),project,sourceId)).thenReturn(0);
        assertThrows(AgentRunConflictException.class,()->service.retry(7L,project,sourceId,request));verify(repository,never()).markRetry(any(),any(),any());
    }
}
