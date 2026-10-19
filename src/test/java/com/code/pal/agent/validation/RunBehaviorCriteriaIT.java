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
    private BehaviorCriteria two(){return new BehaviorCriteria(1,List.of(new BehaviorCriteria.BrowserAction("heading",BehaviorCriteria.BrowserKind.ASSERT_TEXT,"h1","Bakery"),
            new BehaviorCriteria.BrowserAction("menu",BehaviorCriteria.BrowserKind.ASSERT_COUNT,"[data-item]","3")),List.of(),false,false);}
    private UUID followUp(Fixture f,String status){UUID run=UUID.randomUUID();jdbc.update("insert into agent_runs(id,project_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,7,'BUILD',?,0,0,now(),now())",run,f.project(),status);return run;}
    private AgentContext context(Fixture f,UUID run){return new AgentContext(f.project(),7L,ProjectType.BUILD,"stage",null,null,run,new com.code.pal.agent.plan.AgentPlanState(),null);}
    @Test void aFollowUpEditInheritsTheLastCompletedBuildsBrowserChecks() {
        var f=fixture();store.install(f.run(),f.project(),f.projection(),2,two());
        var edit=followUp(f,"RUNNING");
        assertNull(store.regression(context(f,edit)),"an unfinished build passes nothing on");
        jdbc.update("update agent_runs set status='DONE' where id=?",f.run());
        assertEquals(two().browser(),store.regression(context(f,edit)).browser());
        assertNull(store.current(context(f,edit)),"inherited checks are not the edit's own required acceptance");
        jdbc.update("update requirement_sessions set answer_revision=3 where id=?",f.session());
        assertNull(store.regression(context(f,edit)),"changed requirements drop inherited checks");
    }
    @Test void aRetiredCheckStaysRetiredForLaterEditsButOnlyWithAReasonAndAKnownId() {
        var f=fixture();var original=two();
        var advisory=new BehaviorCriteria(1,original.browser(),List.of(),false,false,BehaviorCriteria.Provenance.MODEL_SPEC);
        store.install(f.run(),f.project(),f.projection(),2,advisory);jdbc.update("update agent_runs set status='DONE' where id=?",f.run());
        var edit=followUp(f,"RUNNING");
        assertThrows(IllegalArgumentException.class,()->store.retire(context(f,edit),List.of("missing"),"renamed"));
        assertThrows(IllegalArgumentException.class,()->store.retire(context(f,edit),List.of("heading")," "));
        assertThrows(IllegalArgumentException.class,()->store.retire(f.context(),List.of("heading"),"own acceptance"));
        var stopped=followUp(f,"RUNNING");jdbc.update("update agent_runs set cancel_requested_at=now() where id=?",stopped);
        assertThrows(IllegalStateException.class,()->store.retire(context(f,stopped),List.of("heading","menu"),"stopped run"));
        assertEquals(0,jdbc.queryForObject("select count(*) from agent_acceptance_retirements where run_id=?",Integer.class,stopped),"nothing partial");
        assertEquals(List.of("menu"),store.retire(context(f,edit),List.of("heading"),"The user renamed the bakery"));
        assertEquals(List.of("menu"),store.regression(context(f,edit)).browser().stream().map(BehaviorCriteria.BrowserAction::id).toList());
        var later=followUp(f,"RUNNING");
        assertEquals(2,store.regression(context(f,later)).browser().size(),"a failed or unfinished edit's retirement does not stick");
        jdbc.update("update agent_runs set status='DONE' where id=?",edit);
        assertEquals(List.of("menu"),store.regression(context(f,later)).browser().stream().map(BehaviorCriteria.BrowserAction::id).toList());
    }
    @Test
    void blockingInheritedChecksCannotBeRetiredByAModelWrittenReason() {
        for (var provenance : java.util.Arrays.asList(null, BehaviorCriteria.Provenance.PLATFORM)) {
            var f=fixture();var original=two();
            store.install(f.run(),f.project(),f.projection(),2,
                    new BehaviorCriteria(1,original.browser(),List.of(),false,false,provenance));
            jdbc.update("update agent_runs set status='DONE' where id=?",f.run());
            var edit=context(f,followUp(f,"RUNNING"));
            assertThrows(IllegalArgumentException.class,()->store.retire(edit,List.of("heading"),"The user asked for this"));
            assertEquals(2,store.regression(edit).browser().size());
        }
    }

    @Test
    void modelProvenanceSurvivesRestartAndFollowUpRetirement() {
        var f = fixture();
        var original = two();
        var model = new BehaviorCriteria(1, original.browser(), List.of(), false, false, BehaviorCriteria.Provenance.MODEL_SPEC);
        store.install(f.run(), f.project(), f.projection(), 2, model);
        var restarted = new RunBehaviorCriteria(jdbc, mapper, app.getBean(ApprovedProjectMemory.class));
        assertEquals(model, restarted.current(f.context()));
        jdbc.update("update agent_runs set status='DONE' where id=?", f.run());
        var edit = context(f, followUp(f, "RUNNING"));
        assertTrue(restarted.regression(edit).modelSpec());
        store.retire(edit, List.of("heading"), "User removed the heading");
        assertTrue(restarted.regression(edit).modelSpec());
        assertEquals(List.of("menu"), restarted.regression(edit).browser().stream().map(BehaviorCriteria.BrowserAction::id).toList());
    }

    @Configuration @Import({ApprovedProjectMemory.class,RunBehaviorCriteria.class})
    static class Services {
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean BuildDecisions decisions(){var value=mock(BuildDecisions.class);when(value.state(org.mockito.ArgumentMatchers.any())).thenReturn(new BuildDecisions.State(new BuildDecisions.Choice(1,BuildDecisions.Storage.BROWSER,BuildDecisions.Auth.NONE,null),new BuildDecisions.Available(false,null),null));return value;}
    }
}
