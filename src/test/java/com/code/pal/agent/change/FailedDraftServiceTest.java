package com.code.pal.agent.change;

import com.code.pal.agent.domain.*;
import com.code.pal.agent.exception.AgentRunConflictException;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailedDraftServiceTest {
    final LocalProject project=LocalProject.create();
    final FailedDraftRepository repository=mock(FailedDraftRepository.class);
    final ApprovedProjectMemory memory=mock(ApprovedProjectMemory.class);
    final FailedDraftService drafts=new FailedDraftService(repository,project.files,memory,new ObjectMapper());
    final AtomicReference<FailedDraftRepository.Stored> artifact=new AtomicReference<>();
    final AgentRunRepository runs=mock(AgentRunRepository.class);
    final ChangeSetService changes=new ChangeSetService(mock(FileChangeRepository.class),runs,new ObjectMapper(),project.properties,project.files,project.sandbox,mock(ProjectService.class));
    FailedDraftServiceTest() {
        when(repository.find(eq(project.projectId),any())).thenAnswer(call->{var a=artifact.get();return a!=null && a.runId().equals(call.getArgument(1))?a:null;});
        doAnswer(call->{artifact.set(call.getArgument(0));return null;}).when(repository).retain(any());
        changes.useFailedDrafts(drafts);
    }
    @Test void retainsFailedSourceAfterCleanupAndServiceRestartWithoutChangingLiveCode() {
        project.write("App.tsx","old").write(".env","SECRET").write("RENDRA.md","private brief");
        var run=run();when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        String stage=changes.start(run.getId(),project.projectId);
        var context=context(run,stage);changes.patch(context,"App.tsx",project.files.version(project.projectId,stage,"App.tsx"),"old","failed candidate");
        run.setStatus(RunStatus.FAILED);changes.finish(run);
        assertFalse(project.exists(stage));assertEquals("old",project.read("App.tsx"));
        var restarted=new FailedDraftService(repository,project.files,memory,new ObjectMapper());
        assertTrue(restarted.info(project.projectId,run.getId()).available());assertFalse(restarted.info(project.projectId,run.getId()).verified());
        assertEquals("failed candidate",restarted.file(project.projectId,run.getId(),"App.tsx").content());
        assertEquals(List.of("App.tsx"),restarted.info(project.projectId,run.getId()).files().stream().map(FailedDraftArchive.Source::path).toList());
        assertThrows(AgentRunConflictException.class,()->restarted.file(project.projectId,run.getId(),".env"));
    }
    @Test void stoppedDraftSeedsOnlyNewStageAndStillRequiresValidationToSave() {
        project.write("App.tsx","old");var source=capture(RunStatus.CANCELLED);
        var retry=run();retry.setRetrySourceRunId(source.getId());when(runs.findById(retry.getId())).thenReturn(Optional.of(retry));
        String stage=changes.start(retry.getId(),project.projectId);
        assertEquals("candidate",project.read(stage+"/App.tsx"));assertEquals("old",project.read("App.tsx"));
        var denied=assertThrows(com.code.pal.agent.tool.AgentToolException.class,()->changes.commit(context(retry,stage),retry));
        assertEquals("VALIDATION_REQUIRED",denied.code());
    }
    @Test void refusesStaleCodeApprovalAndCorruptArchiveBeforeRetryWrites() {
        project.write("App.tsx","old");var source=capture(RunStatus.FAILED);
        project.write("App.tsx","user edit");assertThrows(AgentRunConflictException.class,()->drafts.retryManifest(project.projectId,source.getId()));
        project.write("App.tsx","old");when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(),2,"{}","brief"));
        assertThrows(AgentRunConflictException.class,()->drafts.retryManifest(project.projectId,source.getId()));
        var a=artifact.get();a.archive()[0]^=1;
        assertThrows(AgentRunConflictException.class,()->drafts.download(project.projectId,source.getId()));
    }
    @Test void retentionFailureKeepsRawStageAndDoesNotMaskFailure() {
        project.write("App.tsx","old");var run=run();when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        String stage=changes.start(run.getId(),project.projectId);changes.patch(context(run,stage),"App.tsx",project.files.version(project.projectId,stage,"App.tsx"),"old","candidate");
        run.setStatus(RunStatus.FAILED);doThrow(new IllegalStateException("database unavailable")).when(repository).retain(any());
        assertDoesNotThrow(()->changes.finish(run));assertTrue(project.exists(stage));assertEquals("old",project.read("App.tsx"));
    }
    @Test void savedAndRecoveryPendingRunsCannotBecomeFailedDrafts() {
        project.write("App.tsx","old");var run=run();run.setStatus(RunStatus.FAILED);run.setCodeSavedAt(java.time.Instant.now());
        assertFalse(drafts.retain(run,".",List.of(new FailedDraftArchive.Change("App.tsx","absent","absent"))));
        run.setCodeSavedAt(null);run.setSaveRecoveryRequired(true);
        assertFalse(FailedDraftService.eligible(run));verify(repository,never()).retain(any());
    }
    @Test void approvalChangedDuringRunDoesNotBecomeTheOriginalDraftBasis() {
        project.write("App.tsx","old");var original=new ApprovedProjectMemory.Approved(UUID.randomUUID(),1,"{\"goal\":\"original\"}","brief");
        when(memory.current(project.projectId)).thenReturn(original);
        var run=run();when(runs.findById(run.getId())).thenReturn(Optional.of(run));String stage=changes.start(run.getId(),project.projectId);
        changes.patch(context(run,stage),"App.tsx",project.files.version(project.projectId,stage,"App.tsx"),"old","candidate");
        when(memory.current(project.projectId)).thenReturn(new ApprovedProjectMemory.Approved(UUID.randomUUID(),2,"{\"goal\":\"changed\"}","brief"));
        run.setStatus(RunStatus.FAILED);changes.finish(run);
        assertTrue(drafts.info(project.projectId,run.getId()).available());
        assertThrows(AgentRunConflictException.class,()->drafts.retryManifest(project.projectId,run.getId()));
    }
    @Test void processLossRecoversSourceUsingDurableOriginalBasisAndMutationLedger() {
        project.write("App.tsx","old");var run=run();when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(runs.findByIdAndProjectId(run.getId(),project.projectId)).thenReturn(Optional.of(run));
        String stage=changes.start(run.getId(),project.projectId);String before=project.files.version(project.projectId,stage,"App.tsx");
        changes.patch(context(run,stage),"App.tsx",before,"old","candidate");run.setStatus(RunStatus.FAILED);
        var audit=mock(FileChangeRepository.class);
        when(audit.findByRunIdOrderByCreatedAtAsc(run.getId())).thenReturn(List.of(FileChange.builder().runId(run.getId()).projectId(project.projectId)
                .path("App.tsx").beforeVersion(before).afterVersion(project.files.version(project.projectId,stage,"App.tsx")).operation(FileOperation.PATCH).build()));
        var restarted=new ChangeSetService(audit,runs,new ObjectMapper(),project.properties,project.files,project.sandbox,mock(ProjectService.class));
        restarted.useFailedDrafts(drafts);restarted.cleanupAbandoned(project.projectId,run.getId());
        assertFalse(project.exists(stage));assertEquals("candidate",drafts.file(project.projectId,run.getId(),"App.tsx").content());
    }
    @Test void deletionAndCreationAreReusedWithoutDeletingLiveFiles() {
        project.write("App.tsx","old");var source=run();source.setStatus(RunStatus.FAILED);String root=".codepal/rename-draft";
        project.write(root+"/NewApp.tsx","candidate");
        drafts.retain(source,root,List.of(new FailedDraftArchive.Change("App.tsx",project.files.version(project.projectId,".","App.tsx"),FileVersions.ABSENT),
                new FailedDraftArchive.Change("NewApp.tsx",FileVersions.ABSENT,project.files.version(project.projectId,root,"NewApp.tsx"))));
        var retry=run();retry.setRetrySourceRunId(source.getId());when(runs.findById(retry.getId())).thenReturn(Optional.of(retry));String stage=changes.start(retry.getId(),project.projectId);
        assertFalse(project.exists(stage+"/App.tsx"));assertEquals("candidate",project.read(stage+"/NewApp.tsx"));
        assertEquals("old",project.read("App.tsx"));assertFalse(project.exists("NewApp.tsx"));
    }
    @Test void deletingAllSourceStillProducesARetryableUnverifiedArtifact() {
        project.write("App.tsx","old");var source=run();source.setStatus(RunStatus.CANCELLED);
        project.write(".codepal/delete-only/RENDRA.md","private");
        assertTrue(drafts.retain(source,".codepal/delete-only",List.of(new FailedDraftArchive.Change("App.tsx",project.files.version(project.projectId,".","App.tsx"),FileVersions.ABSENT))));
        assertTrue(drafts.info(project.projectId,source.getId()).files().isEmpty());
        var retry=run();retry.setRetrySourceRunId(source.getId());when(runs.findById(retry.getId())).thenReturn(Optional.of(retry));
        String stage=changes.start(retry.getId(),project.projectId);
        assertFalse(project.exists(stage+"/App.tsx"));assertEquals("old",project.read("App.tsx"));
    }
    private AgentRun capture(RunStatus status) {
        var run=run();String root=".codepal/test-draft";project.write(root+"/App.tsx","candidate");run.setStatus(status);
        assertTrue(drafts.retain(run,root,List.of(new FailedDraftArchive.Change("App.tsx",project.files.version(project.projectId,".","App.tsx"),project.files.version(project.projectId,root,"App.tsx")))));
        return run;
    }
    AgentRun run(){return AgentRun.builder().id(UUID.randomUUID()).projectId(project.projectId).userId(7L).mode(ProjectType.BUILD).status(RunStatus.RUNNING).build();}
    AgentContext context(AgentRun run,String stage){var plan=new AgentPlanState();plan.revise(List.of("Update app","Validate"),"Retry fixture");return new AgentContext(project.projectId,7L,ProjectType.BUILD,stage,null,new com.code.pal.agent.context.ProjectContextTrace(),run.getId(),plan,project.files);}
}
