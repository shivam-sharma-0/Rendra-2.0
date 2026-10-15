package com.code.pal.agent.contract;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.*;
import com.code.pal.agent.lease.*;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.*;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.*;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.requirement.RequirementProjection;
import com.code.pal.project.service.ProjectService;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContractStoreIT {
    AnnotationConfigApplicationContext app;JdbcTemplate jdbc;ContractStore store;ObjectMapper mapper;ProjectFiles files;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);store=app.getBean(ContractStore.class);mapper=app.getBean(ObjectMapper.class);files=app.getBean(ProjectFiles.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    class Fixture {
        final UUID project=UUID.randomUUID(),session=UUID.randomUUID(),projection=UUID.randomUUID();
        final AgentRun plan;final BuildContract contract;final ContractStore.Binding basis;
        Fixture(){
            jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Disposable contract','BUILD',false,'NONE',now(),now(),now())",project,UUID.randomUUID());
            var sessionRow=app.getBean(RequirementSessionRepository.class).saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("Calculator").status(RequirementStatus.APPROVED).answerRevision(2).approvedRevision(2L).build());
            String req=mapper.writeValueAsString(Map.of("answers",List.of(Map.of("question","Feature","answer","Calculator"))));
            jdbc.update("insert into requirement_projections(id,session_id,project_id,revision,payload_json,state) values (?,?,?,?,?,'READY')",projection,sessionRow.getId(),project,2,mapper.writeValueAsString(new RequirementProjection.Payload(req,"Approved")));
            jdbc.update("update requirement_sessions set projection_id=? where id=?",projection,sessionRow.getId());
            files.writeText(project,".","index.html","before");plan=run(ProjectType.PLAN);
            var c=ContractFixtures.staticSite();contract=new BuildContract(c.schemaVersion(),UUID.randomUUID(),c.stack(),c.storage(),c.auth(),c.limits(),c.theme(),c.routes(),c.types(),c.models(),c.api(),c.assets(),c.envNames(),c.units(),c.acceptance());basis=store.current(project);
        }
        AgentRun run(ProjectType mode){return app.getBean(AgentRunRepository.class).saveAndFlush(AgentRun.builder().projectId(project).userId(7L).mode(mode).status(RunStatus.RUNNING).build());}
        ContractStore.Stored draft(){return store.create(plan,contract,basis,contract.limits());}
        void done(){jdbc.update("update agent_runs set status='DONE' where id=?",plan.getId());}
    }
    @Test void approvedPlanReusesTheSameImmutableContractAcrossBuildRunsAndRestart() {
        var f=new Fixture();var draft=f.draft();assertEquals("DRAFT",draft.state());f.done();
        assertEquals("APPROVED",store.approve(7L,f.project,f.plan.getId(),f.contract.id(),draft.hash()).state());
        var reuse=store.reusable(f.project,"build the approved plan",f.contract.limits());assertNotNull(reuse);
        var build=f.run(ProjectType.BUILD);var attached=store.attach(build,reuse,f.contract.limits());
        assertEquals(f.contract,attached.contract());assertEquals(f.contract.id(),store.forRun(f.project,build.getId()).contract().id());
        assertEquals(1,jdbc.queryForObject("select count(*) from agent_build_contracts where project_id=?",Integer.class,f.project));
        assertEquals("before",files.readText(f.project,".","index.html"));
        assertNull(store.reusable(f.project,"build a different app",f.contract.limits()));
        assertEquals(draft.hash(),store.view(f.project,f.plan.getId()).hash());
        var restarted=new ContractStore(jdbc,app.getBean(org.springframework.transaction.PlatformTransactionManager.class),mapper,files,
                app.getBean(ApprovedProjectMemory.class),app.getBean(BuildDecisions.class),app.getBean(ProjectLeaseService.class),app.getBean(ProjectService.class));
        assertEquals("APPROVED",restarted.forRun(f.project,build.getId()).state());
        assertEquals(f.contract,restarted.reusable(f.project,"build the approved plan",f.contract.limits()).contract());
    }
    @Test void staleCodeRequirementsCapabilitiesAndReviewedHashCannotApproveOrReuse() {
        var f=new Fixture();var draft=f.draft();f.done();
        assertThrows(RuntimeException.class,()->store.approve(7L,f.project,f.plan.getId(),f.contract.id(),"wrong"));
        files.writeText(f.project,".","index.html","edited");
        assertThrows(RuntimeException.class,()->store.approve(7L,f.project,f.plan.getId(),f.contract.id(),draft.hash()));
        assertEquals("INVALIDATED",store.view(f.project,f.plan.getId()).state());
        assertThrows(RuntimeException.class,()->store.reusable(f.project,"build the approved plan",f.contract.limits()));
        var other=new Fixture();other.draft();other.done();jdbc.update("update requirement_sessions set answer_revision=3 where project_id=?",other.project);
        assertThrows(RuntimeException.class,()->store.approve(7L,other.project,other.plan.getId(),other.contract.id(),store.forRun(other.project,other.plan.getId()).hash()));
        var changed=new Fixture();var changedDraft=changed.draft();changed.done();
        when(app.getBean(BuildDecisions.class).state(changed.project)).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.NONE,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(false,null),null));
        assertThrows(RuntimeException.class,()->store.approve(7L,changed.project,changed.plan.getId(),changed.contract.id(),changedDraft.hash()));
        assertEquals("INVALIDATED",store.view(changed.project,changed.plan.getId()).state());
    }
    @Test void draftCrossProjectAndRaisedBudgetBindingsAreRefused() {
        var f=new Fixture();f.draft();assertThrows(RuntimeException.class,()->store.reusable(f.project,"start",f.contract.limits()));
        var build=f.run(ProjectType.BUILD);assertThrows(RuntimeException.class,()->store.attach(build,store.forRun(f.project,f.plan.getId()),f.contract.limits()));
        assertThrows(RuntimeException.class,()->store.create(f.plan,f.contract,f.basis,new BuildContract.Limits(1,1,1,1)));
        f.done();store.approve(7L,f.project,f.plan.getId(),f.contract.id(),store.forRun(f.project,f.plan.getId()).hash());
        var other=new Fixture();assertThrows(RuntimeException.class,()->store.attach(other.run(ProjectType.BUILD),store.forRun(f.project,f.plan.getId()),f.contract.limits()));
    }
    @Configuration @Import({ContractStore.class,ApprovedProjectMemory.class,ProjectLeaseService.class,ProjectMutationLocks.class})
    static class Services {
        @Bean AgentProperties props(org.springframework.core.env.Environment env){var p=new AgentProperties();p.setWorkspaceBasePath(env.getRequiredProperty("app.agent.workspace-base-path"));return p;}
        @Bean WorkspaceStorage storage(AgentProperties p){return new WorkspaceStorage(p);}
        @Bean ProjectFiles files(WorkspaceStorage storage){return new HostProjectFiles(storage);}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean ProjectService projects(){return mock(ProjectService.class);}
        @Bean BuildDecisions decisions(){var d=mock(BuildDecisions.class);when(d.state(any())).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.NONE,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(true,null),null));return d;}
    }
}
