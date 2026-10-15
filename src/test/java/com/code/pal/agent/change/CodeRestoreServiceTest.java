package com.code.pal.agent.change;

import com.code.pal.agent.domain.*;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeRestoreServiceTest {
    final LocalProject p=LocalProject.create();
    final PromotionJournal promotions=mock(PromotionJournal.class);
    final RestoreJournal journal=mock(RestoreJournal.class);
    final ProjectLeaseService leases=mock(ProjectLeaseService.class);
    final BuildDecisions decisions=mock(BuildDecisions.class);
    final AgentRunRepository runs=mock(AgentRunRepository.class);
    final AgentRun run=AgentRun.builder().id(UUID.randomUUID()).projectId(p.projectId).status(RunStatus.DONE).codeSavedAt(Instant.now()).build();
    final String archive=".codepal/history/"+run.getId()+"/original";
    final CodeRestoreService service=new CodeRestoreService(leases,promotions,journal,p.files,decisions,mock(ProjectService.class),runs);
    CodeRestoreServiceTest() {
        doAnswer(c->{((Runnable)c.getArgument(1)).run();return null;}).when(leases).manualRestore(any(),any());
        when(runs.findTopByProjectIdOrderByCreatedAtDesc(p.projectId)).thenReturn(Optional.of(run));
        when(decisions.state(p.projectId)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(false,null),null));
        p.write("index.html","after").write("RENDRA.md","after brief").write("user.txt","unrelated");
        p.write(archive+"/index.html","before").write(archive+"/RENDRA.md","before brief");
        var proof=List.of(file("index.html"),file("RENDRA.md"));
        when(promotions.read(run.getId())).thenReturn(new PromotionJournal.Entry(run.getId(),p.projectId,null,1,1,proof,"SAVED",Instant.now()));
        when(journal.readState(any())).thenReturn("APPLYING");
    }
    PromotionJournal.FileState file(String path) {return new PromotionJournal.FileState(path,p.files.version(p.projectId,archive,path),p.files.version(p.projectId,".",path));}
    @Test void restoresMatchingCodeBriefAndPreservesUnrelatedEdits() {
        service.restore(1L,run);
        assertEquals("before",p.read("index.html")); assertEquals("before brief",p.read("RENDRA.md")); assertEquals("unrelated",p.read("user.txt"));
        verify(journal).state(any(),eq("RESTORED"));
    }
    @Test void refusesNewerCodeAndServerSchemaBeforeAnyMutation() {
        p.write("index.html","user changed"); assertThrows(RuntimeException.class,()->service.restore(1L,run));
        verifyNoInteractions(journal);
        p.write("index.html","after").write("server/package.json","{}");
        assertThrows(RuntimeException.class,()->service.restore(1L,run)); verifyNoInteractions(journal);
    }
    @Test void failedSecondWriteRestoresCurrentTouchedFiles() {
        ProjectFiles files=spy(p.files); var once=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(c->{if(c.getArgument(1).equals(archive) && once.compareAndSet(false,true))throw new IllegalStateException("fixture write failure");return c.callRealMethod();})
                .when(files).copyFile(any(),anyString(),anyString(),eq("."),anyString());
        var service=new CodeRestoreService(leases,promotions,journal,files,decisions,mock(ProjectService.class),runs);
        assertThrows(RuntimeException.class,()->service.restore(1L,run));
        assertEquals("after",p.read("index.html"));assertEquals("after brief",p.read("RENDRA.md"));verify(journal).state(any(),eq("ROLLED_BACK"));
    }
    @Test void uncertainCompensationRetainsRecoveryEvidence() {
        ProjectFiles files=spy(p.files);
        doThrow(new IllegalStateException("fixture uncertain write")).when(files).copyFile(any(),anyString(),anyString(),eq("."),anyString());
        var service=new CodeRestoreService(leases,promotions,journal,files,decisions,mock(ProjectService.class),runs);
        assertThrows(RuntimeException.class,()->service.restore(1L,run));
        verify(journal).state(any(),eq("RECOVERY_REQUIRED"));
    }
}
