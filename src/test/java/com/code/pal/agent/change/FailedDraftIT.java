package com.code.pal.agent.change;

import com.code.pal.message.service.ConversationProposals;

import com.code.pal.agent.*;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.dispatch.*;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.web.FailedDraftController;
import com.code.pal.agent.workspace.*;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.message.service.impl.MessageServiceImpl;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.domain.*;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.shared.security.AuthPrincipal;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.testsupport.CodexJpaFixture;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.*;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Real PostgreSQL/Flyway/JPA/outbox, production fenced filesystem and controller HTTP handling.
 * Permission/admission/realtime are fixture mocks; no model, worker or public preview is started. */
@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailedDraftIT {
    AnnotationConfigApplicationContext app;
    JdbcTemplate jdbc;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    UUID project() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at,lifecycle_stage) "
                +"values (?,?,7,'Draft fixture','BUILD',false,'NONE',now(),now(),now(),'FAILED')",id,UUID.randomUUID());
        when(app.getBean(ProjectService.class).requireCanEdit(7L,id)).thenReturn(Project.builder().id(id).ownerId(7L).type(ProjectType.BUILD).lifecycleStage(ProjectStage.FAILED).build());
        return id;
    }
    String key(){return UUID.randomUUID().toString().replace("-","");}
    AgentRun failed(UUID project,RunStatus terminal) {
        var files=app.getBean(ProjectFiles.class);files.writeText(project,".","index.html","last working version");
        var request=new CreateMessageRequest("u_"+key(),"Build a todo application",ProjectType.BUILD,"a_"+key());
        app.getBean(MessageService.class).post(7L,project,request);
        var runs=app.getBean(AgentRunRepository.class);var run=runs.findByProjectIdAndRequestMessageId(project,request.id()).orElseThrow();
        jdbc.update("update agent_runs set status='RUNNING' where id=?",run.getId());run.setStatus(RunStatus.RUNNING);
        var lease=app.getBean(ProjectLeaseService.class);var fence=lease.claim(project,run.getId(),UUID.randomUUID());assertNotNull(fence);
        try(var ignored=RunFence.bind(fence)) {
            var changes=app.getBean(ChangeSetService.class);String stage=changes.start(run.getId(),project);
            var plan=new AgentPlanState();plan.revise(List.of("Edit app","Validate"),"Fixture");
            var ctx=new AgentContext(project,7L,ProjectType.BUILD,stage,null,new com.code.pal.agent.context.ProjectContextTrace(),run.getId(),plan,files);
            changes.patch(ctx,"index.html",files.version(project,stage,"index.html"),"last working version","unverified failed version");
            jdbc.update("update agent_runs set status=? where id=?",terminal.name(),run.getId());run.setStatus(terminal);changes.finish(run);
        }finally{lease.release(fence);}
        assertEquals("last working version",files.readText(project,".","index.html"));return run;
    }
    MockMvc http() {
        var resolver=new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter parameter){return parameter.getParameterType()==AuthPrincipal.class;}
            public Object resolveArgument(MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer m,
                    org.springframework.web.context.request.NativeWebRequest w,org.springframework.web.bind.support.WebDataBinderFactory b){return new AuthPrincipal(7L,UUID.randomUUID(),"fixture@example.invalid","USER");}
        };
        return standaloneSetup(app.getBean(FailedDraftController.class)).setCustomArgumentResolvers(resolver).build();
    }
    @Test void failedAndStoppedSourceSurvivesCleanupAndIsInspectibleAndDownloadableOverHttp() throws Exception {
        for(var status:List.of(RunStatus.FAILED,RunStatus.CANCELLED)) {
            UUID project=project();var run=failed(project,status);String url="/api/projects/"+project+"/agent-runs/"+run.getId()+"/failed-draft";
            var client=http();client.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.verified").value(false));
            client.perform(get(url+"/file").param("path","index.html")).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("unverified failed version"));
            var download=client.perform(get(url+"/download")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                    .andExpect(content().contentType("application/zip")).andReturn().getResponse().getContentAsByteArray();
            var freshService=new FailedDraftService(app.getBean(FailedDraftRepository.class),app.getBean(ProjectFiles.class),app.getBean(ApprovedProjectMemory.class),new ObjectMapper());
            assertArrayEquals(download,freshService.download(project,run.getId()));
            // Fenced cleanup removes this epoch; an empty run parent may remain for another epoch's evidence.
            assertTrue(app.getBean(ProjectFiles.class).list(project,".codepal/changesets",4).stream()
                    .noneMatch(e->!e.directory() && e.path().contains(run.getId().toString())));
        }
    }
    @Test void retryHttpAcceptanceIsAtomicIdempotentAndSeedsOnlyTheNewWorkerStage() throws Exception {
        UUID project=project();var source=failed(project,RunStatus.FAILED);var request=new FailedDraftRetryService.Request("u_"+key(),"a_"+key());
        String url="/api/projects/"+project+"/agent-runs/"+source.getId()+"/failed-draft/retry";
        var response=http().perform(post(url).contentType("application/json").content(new ObjectMapper().writeValueAsString(request))).andExpect(status().isOk()).andReturn().getResponse();
        UUID retryId=UUID.fromString(new ObjectMapper().readTree(response.getContentAsString()).path("id").asString());
        var retry=app.getBean(FailedDraftRetryService.class).retry(7L,project,source.getId(),request);assertEquals(retryId,retry.getId());
        assertEquals(2,app.getBean(AgentRunRepository.class).countByProjectId(project));
        assertEquals(4,app.getBean(MessageRepository.class).countByProjectId(project));
        assertEquals(2,jdbc.queryForObject("select count(*) from agent_dispatch_outbox o join agent_runs r on r.id=o.run_id where r.project_id=?",Integer.class,project));
        var stored=app.getBean(AgentRunRepository.class).findById(retryId).orElseThrow();assertEquals(source.getId(),stored.getRetrySourceRunId());
        jdbc.update("update agent_runs set status='RUNNING' where id=?",retryId);stored.setStatus(RunStatus.RUNNING);
        var leases=app.getBean(ProjectLeaseService.class);var fence=leases.claim(project,retryId,UUID.randomUUID());assertNotNull(fence);
        try(var ignored=RunFence.bind(fence)) {
            String stage=app.getBean(ChangeSetService.class).start(retryId,project);
            assertEquals("unverified failed version",app.getBean(ProjectFiles.class).readText(project,stage,"index.html"));
            assertEquals("last working version",app.getBean(ProjectFiles.class).readText(project,".","index.html"));
            assertEquals(1,app.getBean(FileChangeRepository.class).findByRunIdOrderByCreatedAtAsc(retryId).size());
            app.getBean(ChangeSetService.class).discard(retryId);
        }finally{leases.release(fence);}
    }
    @Test void failureAfterCreatingRetryRollsBackMessagesRunOutboxAndSourceBinding() {
        UUID project=project();var source=failed(project,RunStatus.FAILED);String constraint="draft_retry_fault_"+key();
        jdbc.execute("alter table agent_failed_drafts add constraint "+constraint+" check(run_id <> '"+source.getId()+"' or retry_run_id is null)");
        try {
            assertThrows(RuntimeException.class,()->app.getBean(FailedDraftRetryService.class).retry(7L,project,source.getId(),new FailedDraftRetryService.Request("u_"+key(),"a_"+key())));
            assertEquals(1,app.getBean(AgentRunRepository.class).countByProjectId(project));assertEquals(2,app.getBean(MessageRepository.class).countByProjectId(project));
            assertNull(app.getBean(FailedDraftRepository.class).find(project,source.getId()).retryRunId());
            assertEquals(1,jdbc.queryForObject("select count(*) from agent_dispatch_outbox o join agent_runs r on r.id=o.run_id where r.project_id=?",Integer.class,project));
        }finally{jdbc.execute("alter table agent_failed_drafts drop constraint "+constraint);}
    }
    @Test void retentionCapKeepsActiveRetrySourceAndSoftDeletePurgesOnlyOwnedProject() {
        UUID project=project(),other=project();var first=failed(project,RunStatus.FAILED);var kept=failed(other,RunStatus.FAILED);
        var retry=app.getBean(FailedDraftRetryService.class).retry(7L,project,first.getId(),new FailedDraftRetryService.Request("u_"+key(),"a_"+key()));
        for(int i=0;i<6;i++)failed(project,RunStatus.FAILED);
        assertEquals("AVAILABLE",app.getBean(FailedDraftRepository.class).find(project,first.getId()).state());
        assertEquals(6,jdbc.queryForObject("select count(*) from agent_failed_drafts where project_id=? and state='AVAILABLE'",Integer.class,project));
        jdbc.update("update agent_runs set status='FAILED' where id=?",retry.getId());failed(project,RunStatus.FAILED);
        assertEquals(5,jdbc.queryForObject("select count(*) from agent_failed_drafts where project_id=? and state='AVAILABLE'",Integer.class,project));
        jdbc.update("update projects set deleted_at=now() where id=?",project);app.getBean(FailedDraftRepository.class).purgeDeleted();
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_failed_drafts where project_id=?",Integer.class,project));
        assertNotNull(app.getBean(FailedDraftRepository.class).find(other,kept.getId()));
    }
    @Test void migrationIsIdempotentAndWrongRunStateCannotRetainSource() {
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V37__failed_build_drafts.sql")).execute(app.getBean(DataSource.class));
        UUID project=project();var source=failed(project,RunStatus.FAILED);var repository=app.getBean(FailedDraftRepository.class);var artifact=repository.find(project,source.getId());
        repository.retain(artifact);assertEquals(1,jdbc.queryForObject("select count(*) from agent_failed_drafts where run_id=?",Integer.class,source.getId()));
        UUID invalid=UUID.randomUUID();jdbc.update("insert into agent_runs(id,project_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,7,'BUILD','DONE',0,0,now(),now())",invalid,project);
        assertThrows(IllegalStateException.class,()->repository.retain(new FailedDraftRepository.Stored(invalid,project,artifact.manifest(),artifact.archive(),artifact.hash(),"AVAILABLE",null)));
    }
    @Configuration
    @Import({ApprovedProjectMemory.class,FailedDraftService.class,FailedDraftRepository.class,FailedDraftRetryService.class,FailedDraftController.class,
            ChangeSetService.class,PromotionJournal.class,SequentialCheckpoints.class,ProjectLeaseService.class,ProjectMutationLocks.class,TrustedProjectIo.class,
            FencedProjectFiles.class,FencedSandboxPort.class,LocalProjectEnvironment.class,MessageServiceImpl.class,ConversationProposals.class,AgentRunCreator.class,AgentTriggerListener.class})
    static class Services {
        @Bean AgentProperties props(org.springframework.core.env.Environment env){var p=new AgentProperties();p.setWorkspaceBasePath(env.getRequiredProperty("app.agent.workspace-base-path"));return p;}
        @Bean WorkspaceStorage storage(AgentProperties p){return new WorkspaceStorage(p);}
        @Bean HostProjectFiles host(WorkspaceStorage s){return new HostProjectFiles(s);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean ProjectService projects(){return mock(ProjectService.class);}
        @Bean RealtimeService realtime(){return mock(RealtimeService.class);}
        @Bean AgentTurnAdmission admission(){return mock(AgentTurnAdmission.class);}
        @Bean MessageSequence sequence(){return new MessageSequence();}
        @Bean AgentDispatchService dispatcher(){return mock(AgentDispatchService.class);}
        @Bean JpaRepositoryFactoryBean<FileChangeRepository,FileChange,UUID> fileChanges(EntityManagerFactory factory){var r=new JpaRepositoryFactoryBean<FileChangeRepository,FileChange,UUID>(FileChangeRepository.class);r.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(factory));r.setTransactionManager("transactionManager");return r;}
    }
}
