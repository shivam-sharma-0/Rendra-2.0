package com.code.pal.agent.change;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.tool.AgentToolException;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.testsupport.CodexJpaFixture;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions and production fenced local files; no workers/providers. */
@EnabledIfSystemProperty(named="codepal.disposable.it", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NewFileBatchIT {
    private AnnotationConfigApplicationContext app;
    @BeforeAll void open() { app = CodexJpaFixture.open(Services.class); }
    @AfterAll void close() { if (app != null) app.close(); }
    private AgentContext tools(UUID project, UUID run) {
        app.getBean(JdbcTemplate.class).update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) "
                + "values (?,?,?,7,'BUILD','RUNNING',0,0,now(),now())", run, project, run.toString());
        var plan = new AgentPlanState(); plan.revise(List.of("Scaffold", "Validate"), "Initial");
        var service = app.getBean(ChangeSetService.class);
        return new AgentContext(project, 7L, ProjectType.BUILD, service.start(run, project), null,
                new com.code.pal.agent.context.ProjectContextTrace(), run, plan, app.getBean(FencedProjectFiles.class));
    }
    private List<ChangeSetService.NewFile> batch() {
        return List.of(new ChangeSetService.NewFile("a.ts", "a"), new ChangeSetService.NewFile("b.ts", "b"));
    }
    @Test void nthPhysicalWriteFailureRollsBackTrackingAndRetryCommitsExactlyOneBatch() {
        var leases = app.getBean(ProjectLeaseService.class);
        var service = app.getBean(ChangeSetService.class);
        var host = app.getBean(HostProjectFiles.class);
        var audit = app.getBean(FileChangeRepository.class);
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var fence = leases.claim(project, run, UUID.randomUUID()); assertNotNull(fence);
        try (var scope = RunFence.bind(fence)) {
            var context = tools(project, run);
            var count = new AtomicInteger();
            doAnswer(call -> {
                Object result = call.callRealMethod();
                if (count.incrementAndGet() == 2) throw new IllegalStateException("Injected after-write failure");
                return result;
            }).when(host).writeText(eq(project), eq(context.root()), anyString(), anyString());
            assertEquals("BATCH_WRITE_FAILED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
            assertTrue(audit.findByRunIdOrderByCreatedAtAsc(run).isEmpty());
            assertEquals(0, service.view(context).revision());
            assertFalse(host.exists(project, context.root(), "a.ts"));
            assertFalse(host.exists(project, context.root(), "b.ts"));
            doCallRealMethod().when(host).writeText(eq(project), eq(context.root()), anyString(), anyString());
            service.createBatch(context, batch());
            assertEquals(2, audit.findByRunIdOrderByCreatedAtAsc(run).size());
            assertEquals(1, service.view(context).revision());
            assertFalse(host.exists(project, ".", "a.ts"));
        } finally { leases.release(fence); }
    }
    @Test void aLaterAuditInsertFailureRollsBackAllRowsAndBlocksSave() {
        var leases = app.getBean(ProjectLeaseService.class);
        var service = app.getBean(ChangeSetService.class);
        var jdbc = app.getBean(JdbcTemplate.class);
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var fence = leases.claim(project, run, UUID.randomUUID()); assertNotNull(fence);
        String constraint = "codex_batch_failure_" + UUID.randomUUID().toString().replace("-", "");
        // Constraint exists only in the fresh schema; reject the second of this run's audit rows.
        jdbc.execute("alter table agent_file_changes add constraint " + constraint + " check (run_id <> '" + run + "' or path <> 'b.ts')");
        try (var scope = RunFence.bind(fence)) {
            var context = tools(project, run);
            assertEquals("BATCH_RECOVERY_REQUIRED", assertThrows(AgentToolException.class, () -> service.createBatch(context, batch())).code());
            assertEquals(0, jdbc.queryForObject("select count(*) from agent_file_changes where run_id=?", Integer.class, run));
            assertThrows(AgentToolException.class, () -> service.recordProjectValidation(context, true, List.of()));
            assertThrows(AgentToolException.class, () -> service.createValidationCandidate(context, "blocked"));
            assertFalse(app.getBean(HostProjectFiles.class).exists(project, ".", "a.ts"));
        } finally { jdbc.execute("alter table agent_file_changes drop constraint " + constraint); leases.release(fence); }
    }
    @Test void oldLeaseCannotBeginAFileBatchAfterTakeover() {
        var leases = app.getBean(ProjectLeaseService.class);
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var old = leases.claim(project, run, UUID.randomUUID()); assertNotNull(old);
        AgentContext context;
        try (var scope = RunFence.bind(old)) { context = tools(project, run); }
        app.getBean(JdbcTemplate.class).update("update agent_project_leases set expires_at=clock_timestamp()-interval '1 second' where project_id=?", project);
        var replacement = leases.claim(project, UUID.randomUUID(), UUID.randomUUID()); assertNotNull(replacement);
        try (var scope = RunFence.bind(old)) {
            assertThrows(LeaseLostException.class, () -> app.getBean(ChangeSetService.class).createBatch(context, batch()));
            assertTrue(app.getBean(FileChangeRepository.class).findByRunIdOrderByCreatedAtAsc(run).isEmpty());
            assertFalse(app.getBean(HostProjectFiles.class).exists(project, context.root(), "a.ts"));
        } finally { leases.release(replacement); }
    }
    @Test void provedPromotionAndNormalRestoreCarryThePrivateBriefWithCode() {
        var leases=app.getBean(ProjectLeaseService.class);var service=app.getBean(ChangeSetService.class);
        var files=app.getBean(FencedProjectFiles.class);var runs=app.getBean(com.code.pal.agent.repository.AgentRunRepository.class);
        UUID project=UUID.randomUUID(),run=UUID.randomUUID();var fence=leases.claim(project,run,UUID.randomUUID());assertNotNull(fence);
        try(var scope=RunFence.bind(fence)) {
            files.writeText(project,".","index.html","accepted");files.writeText(project,".","RENDRA.md","# User notes\nKeep me\n");
            var context=tools(project,run);
            service.patch(context,"index.html",files.version(project,context.root(),"index.html"),"accepted","candidate");
            assertTrue(service.validate(context).valid());service.recordProjectValidation(context,true,List.of());
            service.commit(context,runs.findById(run).orElseThrow());
            var proof=app.getBean(PromotionJournal.class).read(run);assertEquals("SAVED",proof.state());
            assertTrue(proof.files().stream().anyMatch(file->file.path().equals("RENDRA.md")));
            assertEquals("candidate",files.readText(project,".","index.html"));assertTrue(files.readText(project,".","RENDRA.md").contains("Keep me"));
        } finally {leases.release(fence);}
        app.getBean(JdbcTemplate.class).update("update agent_runs set status='DONE' where id=?",run);
        var controller=new com.code.pal.agent.web.AgentRunController(app.getBean(com.code.pal.project.service.ProjectService.class),runs,
                mock(com.code.pal.agent.repository.AgentStepRepository.class),app.getBean(SandboxPort.class),mock(com.code.pal.agent.AgentEvents.class),
                mock(com.code.pal.agent.queue.AgentJobQueue.class),app.getBean(FileChangeRepository.class),service,mock(com.code.pal.agent.preview.SiteBuildService.class),
                mock(com.code.pal.agent.activity.AgentActivityMapper.class),mock(com.code.pal.agent.AgentCancellations.class));
        var principal=mock(com.code.pal.shared.security.AuthPrincipal.class);when(principal.userId()).thenReturn(7L);
        controller.rollback(principal,project,run);
        assertEquals("accepted",files.readText(project,".","index.html"));assertEquals("# User notes\nKeep me\n",files.readText(project,".","RENDRA.md"));
    }
    @Configuration
    @Import({com.code.pal.project.brief.ApprovedProjectMemory.class, ChangeSetService.class, PromotionJournal.class, SequentialCheckpoints.class, ProjectLeaseService.class,
            ProjectMutationLocks.class, TrustedProjectIo.class, FencedProjectFiles.class, FencedSandboxPort.class, LocalProjectEnvironment.class})
    static class Services {
        @Bean AgentProperties props(org.springframework.core.env.Environment env) {
            var props = new AgentProperties(); props.setWorkspaceBasePath(env.getRequiredProperty("app.agent.workspace-base-path")); return props;
        }
        @Bean WorkspaceStorage storage(AgentProperties props) { return new WorkspaceStorage(props); }
        @Bean HostProjectFiles host(WorkspaceStorage storage) { return spy(new HostProjectFiles(storage)); }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean com.code.pal.project.service.ProjectService projects() { return mock(com.code.pal.project.service.ProjectService.class); }
        @Bean JpaRepositoryFactoryBean<FileChangeRepository, FileChange, UUID> fileChanges(EntityManagerFactory factory) {
            var repo = new JpaRepositoryFactoryBean<FileChangeRepository, FileChange, UUID>(FileChangeRepository.class);
            repo.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(factory));
            repo.setTransactionManager("transactionManager"); return repo;
        }
    }
}
