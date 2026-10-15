package com.code.pal.agent.lease;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real journal transactions and native files. No worker, app secrets or model provider. */
@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromotionRecoveryIT {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ProjectLeaseService leases;
    private PromotionJournal journal;
    private AgentRunRepository runs;
    @BeforeAll void open(){context=CodexJpaFixture.open(ProjectLeaseIT.Services.class);jdbc=context.getBean(JdbcTemplate.class);
        leases=context.getBean(ProjectLeaseService.class);journal=context.getBean(PromotionJournal.class);runs=context.getBean(AgentRunRepository.class);}
    @AfterAll void close(){if(context!=null) context.close();}
    private class Fixture {
        final UUID project=UUID.randomUUID(),id=UUID.randomUUID();
        final ProjectFiles files=spy(context.getBean(FencedProjectFiles.class));
        final SandboxPort sandbox=spy(context.getBean(FencedSandboxPort.class));
        final PromotionJournal evidence=spy(journal);
        final ChangeSetService changes=new ChangeSetService(mock(FileChangeRepository.class),runs,context.getBean(tools.jackson.databind.ObjectMapper.class),
                context.getBean(AgentProperties.class),files,sandbox,context.getBean(com.code.pal.project.service.ProjectService.class));
        final AgentRun run;
        final RunFence fence;
        AgentContext tools;
        Fixture(){
            jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?, ?, ?,7,'BUILD','RUNNING',0,0,now(),now())",id,project,id.toString());
            run=runs.findById(id).orElseThrow();fence=leases.claim(project,id,UUID.randomUUID());assertNotNull(fence);
            ReflectionTestUtils.setField(changes,"leases",leases);ReflectionTestUtils.setField(changes,"journal",evidence);
            files.writeText(project,".","a.txt","before a");files.writeText(project,".","b.txt","before b");files.writeText(project,".","untouched.txt","keep");
        }
        void stage(){
            String root=changes.start(id,project);var plan=new AgentPlanState();plan.revise(List.of("Patch files","Verify"),"fixture");
            tools=new AgentContext(project,7L,ProjectType.BUILD,root,null,new ProjectContextTrace(),id,plan,files);
            for(var path:List.of("a.txt","b.txt")) changes.patch(tools,path,files.version(project,root,path),files.readText(project,root,path),"after "+path);
            changes.recordProjectValidation(tools,true,List.of());
        }
        void expired(){jdbc.update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?",project);}
        void failSecondApply(){doThrow(new IllegalStateException("fixture Nth write")).when(files).copyFile(project,tools.root(),"b.txt",".","b.txt");}
        void recover(){expired();leases.recovering(project,()->changes.recoverPromotion(runs.findById(id).orElseThrow()));}
        void original(){assertEquals("before a",files.readText(project,".","a.txt"));assertEquals("before b",files.readText(project,".","b.txt"));assertEquals("keep",files.readText(project,".","untouched.txt"));}
    }
    @Test void nthApplyFailureRestoresOnlyTouchedFilesWithVerifiedProof(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();f.failSecondApply();assertThrows(IllegalStateException.class,()->f.changes.commit(f.tools,f.run));}
        f.original();assertEquals("ROLLED_BACK",journal.read(f.id).state());assertNull(runs.findById(f.id).orElseThrow().getCodeSavedAt());
    }
    @Test void incompleteArchiveNeverBecomesRestoreEvidenceOrChangesAcceptedFiles(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            f.stage();doAnswer(call->{call.callRealMethod();f.files.delete(f.project,".codepal/history/"+f.id+"/original","b.txt");return null;})
                    .when(f.sandbox).copyTree(f.project,".",".codepal/history/"+f.id+"/original");
            assertThrows(IllegalStateException.class,()->f.changes.commit(f.tools,f.run));
        }
        f.original();assertEquals("ROLLED_BACK",journal.read(f.id).state());assertNull(runs.findById(f.id).orElseThrow().getSnapshotRef());
    }
    @Test void lostFinalizationResponseDoesNotUndoDurablySavedFiles(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            f.stage();doAnswer(call->{call.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("fixture lost commit response");})
                    .when(f.evidence).saved(f.id);
            var saved=f.changes.commit(f.tools,f.run);assertEquals(List.of("a.txt","b.txt"),saved.changedPaths());
        }
        assertEquals("after a.txt",f.files.readText(f.project,".","a.txt"));assertEquals("SAVED",journal.read(f.id).state());
        assertNotNull(runs.findById(f.id).orElseThrow().getCodeSavedAt());assertNotNull(f.run.getCodeSavedAt());
    }
    @Test void unavailableFinalizationEvidenceQuarantinesInsteadOfGuessingRollback(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            f.stage();doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture DB outage")).when(f.evidence).saved(f.id);
            doThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture DB outage")).when(f.evidence).read(f.id);
            assertThrows(PromotionRecoveryPendingException.class,()->f.changes.commit(f.tools,f.run));f.changes.discard(f.id);
        }
        assertEquals("APPLYING",journal.read(f.id).state());assertEquals(RunStatus.COMMITTING,runs.findById(f.id).orElseThrow().getStatus());
        assertThrows(PromotionRecoveryPendingException.class,()->f.files.writeText(f.project,".","a.txt","manual overwrite"));
        assertTrue(f.files.exists(f.project,".codepal/changesets/"+f.id+"/epoch-"+f.fence.epoch(),"workspace"));
        f.expired();assertNull(leases.claim(f.project,UUID.randomUUID(),UUID.randomUUID()));
        reset(f.evidence);f.recover();f.original();assertEquals("ROLLED_BACK",journal.read(f.id).state());
    }
    @Test void restoreFailureRetainsEvidenceAndCanRecoverOnTheNextSweep(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            f.stage();f.failSecondApply();doThrow(new IllegalStateException("fixture restore failed")).when(f.files)
                    .copyFile(f.project,".codepal/history/"+f.id+"/original","a.txt",".","a.txt");
            assertThrows(PromotionRecoveryPendingException.class,()->f.changes.commit(f.tools,f.run));
        }
        assertEquals("RECOVERY_REQUIRED",journal.read(f.id).state());assertTrue(leases.hasPendingPromotion(f.project));
        assertTrue(runs.findById(f.id).orElseThrow().isSaveRecoveryRequired());
        reset(f.files);f.recover();f.original();assertFalse(leases.hasPendingPromotion(f.project));
    }
    @Test void unknownExternalEditIsPreservedAndRecoveryCannotClaimSuccess(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            f.stage();f.failSecondApply();doAnswer(call->{context.getBean(HostProjectFiles.class).writeText(f.project,".","a.txt","external edit");throw new IllegalStateException("fixture failure");})
                    .when(f.files).copyFile(f.project,f.tools.root(),"b.txt",".","b.txt");
            assertThrows(PromotionRecoveryPendingException.class,()->f.changes.commit(f.tools,f.run));
        }
        assertThrows(PromotionRecoveryPendingException.class,f::recover);assertEquals("external edit",f.files.readText(f.project,".","a.txt"));
        assertTrue(leases.hasPendingPromotion(f.project));assertNull(leases.claim(f.project,UUID.randomUUID(),UUID.randomUUID()));
    }
    @Test void aCrashDuringApplyRestoresWithProofAndKeepsUnrelatedNewContent(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            var proof=List.of(new PromotionJournal.FileState("a.txt",f.files.version(f.project,".","a.txt"),hash(f,"after a")));
            assertTrue(journal.prepare(f.run,1,proof));f.sandbox.copyTree(f.project,".",".codepal/history/"+f.id+"/original");journal.ready(f.id);journal.applying(f.id);
            f.files.writeText(f.project,".","a.txt","after a");
        }
        context.getBean(HostProjectFiles.class).writeText(f.project,".","unrelated.txt","new external content");
        f.recover();f.original();assertEquals("new external content",f.files.readText(f.project,".","unrelated.txt"));assertEquals("ROLLED_BACK",journal.read(f.id).state());
    }
    @Test void savedPreviewRecoveryNeverRestoresOverSubsequentUserEdits(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();f.changes.commit(f.tools,f.run);}
        f.files.writeText(f.project,".","a.txt","later edit");f.recover();assertEquals("later edit",f.files.readText(f.project,".","a.txt"));
        assertNotNull(runs.findById(f.id).orElseThrow().getCodeSavedAt());
    }
    @Test void expiredOwnerCannotFinalizeOrReleasePromotionEvidenceDirectly() {
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            var proof=List.of(new PromotionJournal.FileState("a.txt",f.files.version(f.project,".","a.txt"),hash(f,"after a")));
            assertTrue(journal.prepare(f.run,1,proof));f.sandbox.copyTree(f.project,".",".codepal/history/"+f.id+"/original");journal.ready(f.id);journal.applying(f.id);
            f.expired();assertThrows(IllegalStateException.class,()->journal.saved(f.id));
            assertThrows(LeaseLostException.class,()->journal.recoveryState(f.id,"ROLLED_BACK"));
        }
        assertThrows(IllegalStateException.class,()->journal.recoveryState(f.id,"ROLLED_BACK"));
        assertEquals("APPLYING",journal.read(f.id).state());assertNull(runs.findById(f.id).orElseThrow().getCodeSavedAt());f.recover();f.original();
    }
    @Test void legacyIncompleteArchiveCannotTriggerDestructiveAutomaticRestore(){
        var f=new Fixture();jdbc.update("update agent_runs set status='COMMITTING',snapshot_ref=? where id=?","archive:"+f.id,f.id);
        assertThrows(PromotionRecoveryPendingException.class,f::recover);f.original();assertNull(leases.claim(f.project,UUID.randomUUID(),UUID.randomUUID()));
    }
    private String hash(Fixture f,String value){f.files.writeText(f.project,".codepal/hash-fixture","file",value);return f.files.version(f.project,".codepal/hash-fixture","file");}
}
