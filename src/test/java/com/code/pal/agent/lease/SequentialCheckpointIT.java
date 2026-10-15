package com.code.pal.agent.lease;

import com.code.pal.agent.change.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.context.ProjectContextTrace;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.route.*;
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

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SequentialCheckpointIT {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ProjectLeaseService leases;
    private SequentialCheckpoints checkpoints;
    private AgentRunRepository runs;
    @BeforeAll void open(){context=CodexJpaFixture.open(ProjectLeaseIT.Services.class);jdbc=context.getBean(JdbcTemplate.class);
        leases=context.getBean(ProjectLeaseService.class);checkpoints=context.getBean(SequentialCheckpoints.class);runs=context.getBean(AgentRunRepository.class);}
    @AfterAll void close(){if(context!=null) context.close();}
    private class Fixture {
        final UUID project=UUID.randomUUID(),id=UUID.randomUUID();
        final ProjectFiles files=context.getBean(FencedProjectFiles.class);
        final FileChangeRepository changes=mock(FileChangeRepository.class);
        final ChangeSetService service=new ChangeSetService(changes,runs,context.getBean(tools.jackson.databind.ObjectMapper.class),
                context.getBean(AgentProperties.class),files,context.getBean(FencedSandboxPort.class),context.getBean(com.code.pal.project.service.ProjectService.class));
        final AgentRun run;
        RunFence fence;
        AgentContext tools;
        Fixture(){
            jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,request_fingerprint,prompt_tokens,completion_tokens,created_at,updated_at) values (?, ?, ?,7,'BUILD','RUNNING',?,0,0,now(),now())",id,project,id.toString(),"a".repeat(64));
            run=runs.findById(id).orElseThrow();fence=leases.claim(project,id,UUID.randomUUID());assertNotNull(fence);
            ReflectionTestUtils.setField(service,"leases",leases);ReflectionTestUtils.setField(service,"journal",context.getBean(PromotionJournal.class));
            ReflectionTestUtils.setField(service,"checkpoints",checkpoints);
            files.writeText(project,".","a.txt","accepted");files.writeText(project,".","context.txt","old context");
        }
        AgentContext tools(String root){return new AgentContext(project,7L,ProjectType.BUILD,root,null,new ProjectContextTrace(),id,new AgentPlanState(),files);}
        void stage(){tools=tools(service.start(id,project));tools.plan().revise(List.of("Implement files","Finish remaining feature"),"fixture");
            service.patch(tools,"a.txt",files.version(project,tools.root(),"a.txt"),"accepted","proved output");service.create(tools,"new.txt","proved new file");
            tools.plan().updateStep(1,AgentPlanState.StepStatus.COMPLETED,"Files verified");service.recordProjectValidation(tools,true,List.of());}
        void expire(){jdbc.update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?",project);}
        void replace(){expire();fence=leases.claim(project,id,UUID.randomUUID());assertNotNull(fence);}
        boolean requeue(){expire();var ok=new java.util.concurrent.atomic.AtomicBoolean();leases.recovering(project,()->ok.set(checkpoints.requeue(runs.findById(id).orElseThrow())));return ok.get();}
        void accepted(){assertEquals("accepted",files.readText(project,".","a.txt"));assertFalse(files.exists(project,".","new.txt"));}
        RunSize caps(int count){return new RunSize(SizeTier.S,List.of("fixture"),300_000,count,count,count);}
    }
    @Test void resumeReusesProvedOutputsWithFreshUntouchedContextAndRequiresRevalidation(){
        var f=new Fixture();String old;
        try(var scope=RunFence.bind(f.fence)){f.stage();old=f.tools.root();}
        f.files.writeText(f.project,".","context.txt","current context");f.replace();
        try(var scope=RunFence.bind(f.fence)){
            var resumed=f.tools(f.service.start(f.id,f.project));assertNotEquals(old,resumed.root());assertTrue(resumed.root().contains("epoch-2"));
            assertEquals("proved output",f.files.readText(f.project,resumed.root(),"a.txt"));assertEquals("proved new file",f.files.readText(f.project,resumed.root(),"new.txt"));
            assertEquals("current context",f.files.readText(f.project,resumed.root(),"context.txt"));assertNotNull(f.service.resumePlan(resumed));
            assertEquals(AgentPlanState.StepStatus.COMPLETED,resumed.plan().snapshot().steps().getFirst().status());
            assertEquals(AgentPlanState.StepStatus.PENDING,resumed.plan().snapshot().steps().getLast().status());assertTrue(f.service.hasUnvalidatedChanges(resumed));
            assertThrows(com.code.pal.agent.tool.AgentToolException.class,()->f.service.commit(resumed,f.run));
            verify(f.changes,times(2)).save(any(FileChange.class)); // Reuse performs no repeated file tool work.
            context.getBean(HostProjectFiles.class).writeText(f.project,old,"a.txt","late old-stage command");
            assertEquals("proved output",f.files.readText(f.project,resumed.root(),"a.txt"));f.accepted();
        }
    }
    @Test void corruptCheckpointFileIsRejectedBeforeReuse(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();}
        var cp=checkpoints.latest(f.id);context.getBean(HostProjectFiles.class).writeText(f.project,cp.root(),"a.txt","corrupt");f.replace();
        try(var scope=RunFence.bind(f.fence)){assertThrows(IllegalStateException.class,()->f.service.start(f.id,f.project));}f.accepted();
    }
    @Test void changedTouchedBaselineCannotBeOverwrittenByCheckpointReuse(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();}f.files.writeText(f.project,".","a.txt","user changed");f.replace();
        try(var scope=RunFence.bind(f.fence)){assertThrows(IllegalStateException.class,()->f.service.start(f.id,f.project));}
        assertEquals("user changed",f.files.readText(f.project,".","a.txt"));assertFalse(f.files.exists(f.project,".","new.txt"));
    }
    @Test void manifestTamperingFailsItsHashBeforeAnyCopy(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();}
        jdbc.update("update agent_checkpoints set checkpoint_json=checkpoint_json || ' ' where run_id=?",f.id);f.replace();
        try(var scope=RunFence.bind(f.fence)){assertThrows(IllegalStateException.class,()->f.service.start(f.id,f.project));}f.accepted();
    }
    @Test void anotherAcceptedIdentityCannotReuseTheCheckpoint(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){f.stage();f.run.setRequestFingerprint("b".repeat(64));
            assertThrows(IllegalStateException.class,()->checkpoints.restore(f.run,f.tools.root()));}f.accepted();
    }
    @Test void incompleteCheckpointCopyHasNoDurableReusableRow(){
        var f=new Fixture();var failedFiles=spy(f.files);
        var failedStore=new SequentialCheckpoints(jdbc,context.getBean(org.springframework.transaction.PlatformTransactionManager.class),context.getBean(tools.jackson.databind.ObjectMapper.class),
                failedFiles,leases,context.getBean(AgentProperties.class));ReflectionTestUtils.setField(f.service,"checkpoints",failedStore);
        doThrow(new IllegalStateException("fixture checkpoint Nth copy")).when(failedFiles).copyFile(eq(f.project),anyString(),eq("new.txt"),anyString(),eq("new.txt"));
        try(var scope=RunFence.bind(f.fence)){assertThrows(IllegalStateException.class,f::stage);}
        assertNull(checkpoints.latest(f.id));f.accepted();
    }
    @Test void oneResumePreservesCounterCapsDeadlineAndDurableOutbox(){
        var f=new Fixture();long remaining;
        try(var scope=RunFence.bind(f.fence)){
            checkpoints.configure(f.run,f.caps(2));assertTrue(checkpoints.reserveModelIteration(f.run));assertTrue(checkpoints.reserveTools(f.run,1));f.stage();
            remaining=checkpoints.progress(f.id).remainingMs();
        }
        assertTrue(f.requeue());assertEquals(RunStatus.QUEUED,runs.findById(f.id).orElseThrow().getStatus());
        assertEquals(1,jdbc.queryForObject("select count(*) from agent_dispatch_outbox where run_id=? and dispatched_at is null",Integer.class,f.id));
        f.replace();jdbc.update("update agent_runs set status='RUNNING' where id=?",f.id);
        try(var scope=RunFence.bind(f.fence)){
            var progress=checkpoints.configure(f.run,f.caps(100));assertEquals(1,progress.iterations());assertEquals(1,progress.modelCalls());assertEquals(1,progress.toolCalls());
            assertEquals(1,progress.resumeCount());assertTrue(progress.remainingMs()<=remaining);
            assertTrue(checkpoints.reserveModelIteration(f.run));assertFalse(checkpoints.reserveModelIteration(f.run));
            assertFalse(checkpoints.reserveTools(f.run,2));assertTrue(checkpoints.reserveTools(f.run,1));assertFalse(checkpoints.reserveTools(f.run,1));
        }
        assertFalse(f.requeue());assertEquals(1,checkpoints.progress(f.id).resumeCount());
    }
    @Test void anExpiredDeadlineCannotBeResetOrResumed(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            checkpoints.configure(f.run,f.caps(3));f.stage();jdbc.update("update agent_execution_progress set deadline_at=clock_timestamp()-interval '1 second' where run_id=?",f.id);
            assertEquals(0,checkpoints.configure(f.run,f.caps(100)).remainingMs());assertFalse(checkpoints.reserveModelIteration(f.run));
        }
        assertFalse(f.requeue());f.accepted();
    }
    @Test void contractCapsAndDeadlineCanOnlyDecreaseAndRepeatedBindingIsIdempotent(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){
            var original=f.caps(10);checkpoints.configure(f.run,original);
            var limits=new com.code.pal.agent.contract.BuildContract.Limits(2,2,8192,60000);
            checkpoints.restrict(f.run,limits,original);
            var deadline=jdbc.queryForObject("select deadline_at from agent_execution_progress where run_id=?",java.sql.Timestamp.class,f.id);
            checkpoints.restrict(f.run,limits,original);
            assertEquals(deadline,jdbc.queryForObject("select deadline_at from agent_execution_progress where run_id=?",java.sql.Timestamp.class,f.id));
            assertTrue(checkpoints.progress(f.id).remainingMs()<=60000);assertTrue(checkpoints.progress(f.id).remainingMs()>50000);
            assertTrue(checkpoints.reserveModelIteration(f.run));assertTrue(checkpoints.reserveModelIteration(f.run));assertFalse(checkpoints.reserveModelIteration(f.run));
            checkpoints.restrict(f.run,new com.code.pal.agent.contract.BuildContract.Limits(99,99,8192,300000),original);
            assertFalse(checkpoints.reserveModelIteration(f.run));assertEquals(deadline,jdbc.queryForObject("select deadline_at from agent_execution_progress where run_id=?",java.sql.Timestamp.class,f.id));
            assertTrue(checkpoints.reserveTools(f.run,2));assertFalse(checkpoints.reserveTools(f.run,1));
        }
    }
    @Test void acceptedStopPreventsAutomaticResume(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){checkpoints.configure(f.run,f.caps(3));f.stage();}
        assertEquals(1,runs.recordCancellation(f.id,f.project,7L,java.time.Instant.now()));assertFalse(f.requeue());f.accepted();
    }
    @Test void wrongConfigCannotReuseOrAutoResume(){
        var f=new Fixture();try(var scope=RunFence.bind(f.fence)){checkpoints.configure(f.run,f.caps(3));f.stage();}
        var props=context.getBean(AgentProperties.class);int original=props.getMaxRepairAttempts();props.setMaxRepairAttempts(original+1);
        try{assertThrows(IllegalStateException.class,()->f.requeue());f.accepted();}finally{props.setMaxRepairAttempts(original);}
    }
}
