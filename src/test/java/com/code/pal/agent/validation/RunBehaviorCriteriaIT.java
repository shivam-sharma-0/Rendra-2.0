package com.code.pal.agent.validation;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.brief.ApprovedProjectMemory;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.domain.*;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.requirement.RequirementProjection;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunBehaviorCriteriaIT {
    private AnnotationConfigApplicationContext app;private JdbcTemplate jdbc;private ObjectMapper mapper;private RunBehaviorCriteria store;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);mapper=app.getBean(ObjectMapper.class);store=app.getBean(RunBehaviorCriteria.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    private record Fixture(UUID project,UUID run,UUID session,UUID projection) {AgentContext context(){return new AgentContext(project,7L,ProjectType.BUILD,"stage",null,null,run,new com.code.pal.agent.plan.AgentPlanState(),null);}}
    private Fixture fixture() {
        UUID project=UUID.randomUUID(),run=UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Disposable behavior','BUILD',false,'NONE',now(),now(),now())",project,UUID.randomUUID());
        jdbc.update("insert into agent_runs(id,project_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,7,'BUILD','RUNNING',0,0,now(),now())",run,project);
        var session=app.getBean(RequirementSessionRepository.class).saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("Disposable behavior").status(RequirementStatus.APPROVED).answerRevision(2).approvedRevision(2L).build());
        UUID projection=UUID.randomUUID();String answers=mapper.writeValueAsString(Map.of("answers",List.of(Map.of("question","Feature","answer","Calculator"))));
        jdbc.update("insert into requirement_projections(id,session_id,project_id,revision,payload_json,state) values (?,?,?,2,?,'READY')",projection,session.getId(),project,mapper.writeValueAsString(new RequirementProjection.Payload(answers,"# Calculator")));
        jdbc.update("update requirement_sessions set projection_id=? where id=?",projection,session.getId());
        return new Fixture(project,run,session.getId(),projection);
    }
    private BehaviorCriteria criteria(String value){return new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("result",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"#result",value)),List.of(),false,false);}
    @Test void immutableCriteriaSurviveRestartAndCannotBeWeakenedForTheSameRun() {
        var f=fixture();var recipe=criteria("5");store.install(f.run(),f.project(),f.projection(),2,recipe);
        assertEquals(recipe,store.current(f.context()));assertEquals(recipe,new RunBehaviorCriteria(jdbc,mapper,app.getBean(ApprovedProjectMemory.class)).current(f.context()));
        assertThrows(IllegalStateException.class,()->store.install(f.run(),f.project(),f.projection(),2,criteria("0")));assertEquals(recipe,store.current(f.context()));
    }
    @Test void changedOrPendingRequirementsInvalidateTheRecipeAndCrossProjectRunsAreRefused() {
        var f=fixture();store.install(f.run(),f.project(),f.projection(),2,criteria("5"));
        var other=fixture();assertThrows(IllegalStateException.class,()->store.install(f.run(),other.project(),other.projection(),2,criteria("5")));
        jdbc.update("update requirement_projections set state='PENDING' where id=?",f.projection());assertThrows(RuntimeException.class,()->store.current(f.context()));
        jdbc.update("update requirement_projections set state='READY' where id=?",f.projection());jdbc.update("update requirement_sessions set answer_revision=3 where id=?",f.session());assertThrows(RuntimeException.class,()->store.current(f.context()));
    }
    @Test void approvedAccountsCannotDropTheirAuthenticationChecks() {
        var f=fixture();var decisions=app.getBean(BuildDecisions.class);
        when(decisions.state(f.project())).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.MERN,BuildDecisions.Auth.ACCOUNTS,null),new BuildDecisions.Available(true,null),null));
        assertThrows(IllegalArgumentException.class,()->store.install(f.run(),f.project(),f.projection(),2,criteria("5")));
    }
    @Configuration @Import({ApprovedProjectMemory.class,RunBehaviorCriteria.class})
    static class Services {
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean BuildDecisions decisions(){var value=mock(BuildDecisions.class);when(value.state(org.mockito.ArgumentMatchers.any())).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(false,null),null));return value;}
    }
}
