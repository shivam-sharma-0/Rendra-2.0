package com.code.pal.agent.lease;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.runtime.RunCancellation;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectLeaseIT {
    private AnnotationConfigApplicationContext context;
    private ProjectLeaseService leases;
    private JdbcTemplate jdbc;
    private FencedProjectFiles files;
    private AgentRunRepository runs;
    @BeforeAll void open(){context=CodexJpaFixture.open(Services.class);leases=context.getBean(ProjectLeaseService.class);
        jdbc=context.getBean(JdbcTemplate.class);files=context.getBean(FencedProjectFiles.class);runs=context.getBean(AgentRunRepository.class);}
    @AfterAll void close(){if(context!=null) context.close();}
    private void expire(UUID project){jdbc.update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?",project);}
    private AgentRun acceptedRun(UUID p,UUID id) {
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?, ?, ?,7,'BUILD','RUNNING',0,0,now(),now())",id,p,p.toString());
        return runs.findById(id).orElseThrow();
    }
    private RunFence claim(UUID project){var fence=leases.claim(project,UUID.randomUUID(),UUID.randomUUID());assertNotNull(fence);return fence;}
    @Test void concurrentClaimHasOneOwnerAndExpiryCannotBeRenewed() throws Exception {
        UUID p=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<RunFence> work=()->{start.await();return leases.claim(p,UUID.randomUUID(),UUID.randomUUID());};
            var a=pool.submit(work);var b=pool.submit(work);start.countDown();
            var first=a.get(5,TimeUnit.SECONDS);var second=b.get(5,TimeUnit.SECONDS);assertNotEquals(first==null,second==null);
            var owner=first==null?second:first;expire(p);assertFalse(leases.renew(owner));
            var replacement=claim(p);assertEquals(owner.epoch()+1,replacement.epoch());leases.release(owner);
            try(var scope=RunFence.bind(replacement)){leases.requireCurrent(p);}
        }
    }
    @Test void parallelReportedTokenUpdatesPreserveStatusCancellationAndLiveness() throws Exception {
        UUID project=UUID.randomUUID(),id=UUID.randomUUID();acceptedRun(project,id);
        assertEquals(1,runs.recordCancellation(id,project,9L,java.time.Instant.now()));
        jdbc.update("update agent_runs set status='FAILED',heartbeat_at=clock_timestamp(),snapshot_ref='archive-proof' where id=?",id);
        var heartbeat=jdbc.queryForObject("select heartbeat_at from agent_runs where id=?",java.sql.Timestamp.class,id);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->runs.addReportedTokens(id,10,20));var b=pool.submit(()->runs.addReportedTokens(id,30,40));
            assertEquals(1,a.get(2,TimeUnit.SECONDS));assertEquals(1,b.get(2,TimeUnit.SECONDS));
        }
        var row=runs.findById(id).orElseThrow();assertEquals(40,row.getPromptTokens());assertEquals(60,row.getCompletionTokens());
        assertEquals(RunStatus.FAILED,row.getStatus());assertEquals(9L,row.getCancelRequestedBy());assertNotNull(row.getCancelRequestedAt());assertEquals("archive-proof",row.getSnapshotRef());
        assertEquals(heartbeat,jdbc.queryForObject("select heartbeat_at from agent_runs where id=?",java.sql.Timestamp.class,id));
    }
    @Test void oldWorkerCannotWriteCopyRenameDeleteOrOverwriteRunAfterTakeover() {
        UUID p=UUID.randomUUID();var old=claim(p);
        files.writeText(p,".","accepted.txt","accepted");files.writeText(p,".","source.txt","source");
        var run=acceptedRun(p,old.runId());
        expire(p);var replacement=claim(p);
        try(var scope=RunFence.bind(old)) {
            assertThrows(LeaseLostException.class,()->files.writeText(p,".","accepted.txt","stale"));
            assertThrows(LeaseLostException.class,()->files.copyFile(p,".","source.txt",".","accepted.txt"));
            assertThrows(LeaseLostException.class,()->files.move(p,".","source.txt","accepted.txt"));
            assertThrows(LeaseLostException.class,()->files.delete(p,".","accepted.txt"));
            assertThrows(LeaseLostException.class,()->leases.guarded(p,()->{run.setStatus(RunStatus.DONE);return runs.save(run);}));
        }
        assertEquals("accepted",files.readText(p,".","accepted.txt"));assertEquals(RunStatus.RUNNING,runs.findById(run.getId()).orElseThrow().getStatus());
        try(var scope=RunFence.bind(replacement)){files.writeText(p,".","accepted.txt","replacement");}
        assertEquals("replacement",files.readText(p,".","accepted.txt"));
    }
    @Test void takeoverAndManualWritesWaitForAnInflightCriticalMutationWhileRenewalContinues() throws Exception {
        UUID p=UUID.randomUUID();var old=claim(p);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var mutation=pool.submit(()->{try(var scope=RunFence.bind(old)){leases.guarded(p,()->{
                entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
            });}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(leases.renew(old),"renewal must not wait for the advisory critical section");
            expire(p);
            var takeover=pool.submit(()->leases.claim(p,UUID.randomUUID(),UUID.randomUUID()));
            var manual=pool.submit(()->files.writeText(p,".","manual.txt","ok"));
            assertThrows(TimeoutException.class,()->takeover.get(100,TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class,()->manual.get(100,TimeUnit.MILLISECONDS));
            release.countDown();mutation.get(3,TimeUnit.SECONDS);assertNotNull(takeover.get(3,TimeUnit.SECONDS));manual.get(3,TimeUnit.SECONDS);
        } finally {release.countDown();}
    }
    @Test void aRunWithoutItsInternalFenceCannotBypassTheTrustedFileBoundary() {
        UUID p=UUID.randomUUID();try(var signal=RunCancellation.bind(new RunCancellation(UUID.randomUUID()))) {
            assertThrows(LeaseLostException.class,()->files.writeText(p,".","missing.txt","bad"));
        }
        assertFalse(files.exists(p,".","missing.txt"));
    }
    @Test void lostDatabaseConnectionCannotLetTakeoverOverlapInflightFilesystemIo() throws Exception {
        UUID p=UUID.randomUUID();var old=claim(p);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var pid=new java.util.concurrent.atomic.AtomicInteger();var host=context.getBean(HostProjectFiles.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var mutation=pool.submit(()->{try(var scope=RunFence.bind(old)){leases.guarded(p,()->{
                pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));entered.countDown();
                try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                host.writeText(p,".","accepted.txt","inflight completed");
            });}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid.get())),"only this disposable fixture connection is terminated");
            expire(p);var takeover=pool.submit(()->leases.claim(p,UUID.randomUUID(),UUID.randomUUID()));
            assertThrows(TimeoutException.class,()->takeover.get(100,TimeUnit.MILLISECONDS));
            release.countDown();assertThrows(ExecutionException.class,()->mutation.get(3,TimeUnit.SECONDS));
            var replacement=takeover.get(3,TimeUnit.SECONDS);assertNotNull(replacement);
            assertEquals("inflight completed",files.readText(p,".","accepted.txt"));
            try(var scope=RunFence.bind(replacement)){files.writeText(p,".","accepted.txt","new owner");}
            try(var scope=RunFence.bind(old)){assertThrows(LeaseLostException.class,()->files.writeText(p,".","accepted.txt","stale"));}
            assertEquals("new owner",files.readText(p,".","accepted.txt"));
        } finally{release.countDown();}
    }
    @Test void unresolvedPromotionBlocksANewRunClaim() {
        UUID p=UUID.randomUUID();var run=runs.save(AgentRun.builder().projectId(p).messageId(p.toString()).userId(7L).mode(ProjectType.BUILD).status(RunStatus.COMMITTING).build());
        assertNull(leases.claim(p,UUID.randomUUID(),UUID.randomUUID()));
        assertNull(leases.claim(p,run.getId(),UUID.randomUUID()));
    }
    @Test void theActualPromotionBoundaryRefusesAStaleWorkerBeforeArchiving() {
        UUID p=UUID.randomUUID();var old=claim(p);
        var service=context.getBean(com.code.pal.agent.change.ChangeSetService.class);
        var run=acceptedRun(p,old.runId());
        files.writeText(p,".","index.html","accepted");
        com.code.pal.agent.tool.AgentContext tools;
        try(var scope=RunFence.bind(old)) {
            String stage=service.start(old.runId(),p);
            var plan=new com.code.pal.agent.plan.AgentPlanState();plan.revise(List.of("Patch entry", "Validate"),"Initial");
            tools=new com.code.pal.agent.tool.AgentContext(p,7L,ProjectType.BUILD,stage,null,new com.code.pal.agent.context.ProjectContextTrace(),old.runId(),plan,files);
            service.patch(tools,"index.html",files.version(p,stage,"index.html"),"accepted","candidate");
            service.recordProjectValidation(tools,true,List.of());
        }
        expire(p);claim(p);
        try(var scope=RunFence.bind(old)){assertThrows(LeaseLostException.class,()->service.commit(tools,run));}
        assertEquals("accepted",files.readText(p,".","index.html"));
        assertNull(runs.findById(run.getId()).orElseThrow().getSnapshotRef());
        assertFalse(files.exists(p,".codepal/history/"+run.getId(),"original"));
    }
    @Test void authorityQueryFailurePreventsTheActualFilesystemAction() {
        UUID p=UUID.randomUUID();
        var unavailable=new JdbcTemplate(context.getBean(javax.sql.DataSource.class)) {
            @Override public void execute(String sql){throw new org.springframework.dao.DataAccessResourceFailureException("fixture authority unavailable");}
        };
        var failed=new ProjectLeaseService(unavailable,context.getBean(org.springframework.transaction.PlatformTransactionManager.class),
                context.getBean(AgentProperties.class),context.getBean(ProjectMutationLocks.class));
        var host=context.getBean(HostProjectFiles.class);
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->failed.guarded(p,()->host.writeText(p,".","no-write.txt","unsafe")));
        assertFalse(host.exists(p,".","no-write.txt"));
    }
    @Test void uncertainTrustedCommandsQuarantineWritesAndTakeoverUntilOwnedRecoveryConfirmsCleanup() {
        UUID p=UUID.randomUUID();var io=context.getBean(TrustedProjectIo.class);
        UUID operation=io.begin(p,"codepal-trusted-"+UUID.randomUUID(),"fixture-daemon");
        assertNull(leases.claim(p,UUID.randomUUID(),UUID.randomUUID()));
        assertThrows(TrustedOperationPendingException.class,()->files.writeText(p,".","blocked.txt","unsafe"));
        assertFalse(files.exists(p,".","blocked.txt"));
        leases.recovering(p,()->{leases.requireRecovery(p);assertEquals(1,io.pending(p).size());io.finish(operation);});
        assertTrue(io.pending(p).isEmpty());assertNotNull(leases.claim(p,UUID.randomUUID(),UUID.randomUUID()));
    }
    @Configuration @Import({com.code.pal.project.brief.ApprovedProjectMemory.class,com.code.pal.agent.change.SequentialCheckpoints.class,com.code.pal.agent.change.PromotionJournal.class,ProjectLeaseService.class,ProjectMutationLocks.class,TrustedProjectIo.class,FencedProjectFiles.class,FencedSandboxPort.class,LocalProjectEnvironment.class,com.code.pal.agent.change.ChangeSetService.class})
    static class Services {
        @Bean AgentProperties props(org.springframework.core.env.Environment env){var p=new AgentProperties();p.setWorkspaceBasePath(env.getRequiredProperty("app.agent.workspace-base-path"));return p;}
        @Bean WorkspaceStorage storage(AgentProperties p){return new WorkspaceStorage(p);}
        @Bean HostProjectFiles host(WorkspaceStorage s){return new HostProjectFiles(s);}
        @Bean tools.jackson.databind.ObjectMapper mapper(){return new tools.jackson.databind.ObjectMapper();}
        @Bean com.code.pal.agent.change.FileChangeRepository changes(){return org.mockito.Mockito.mock(com.code.pal.agent.change.FileChangeRepository.class);}
        @Bean com.code.pal.project.service.ProjectService projects(){return org.mockito.Mockito.mock(com.code.pal.project.service.ProjectService.class);}
    }
}
