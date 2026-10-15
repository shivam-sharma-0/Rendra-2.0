package com.code.pal.project.capability;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.project.domain.RequirementSession;
import com.code.pal.project.domain.RequirementStatus;
import com.code.pal.project.repository.RequirementSessionRepository;
import com.code.pal.project.supabase.SupabaseConnection;
import com.code.pal.project.supabase.SupabaseConnectionRepository;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildDecisionsIT {
    AnnotationConfigApplicationContext context;
    BuildDecisions decisions;
    RequirementSessionRepository sessions;
    SupabaseConnectionRepository connections;
    JdbcTemplate jdbc;
    @BeforeAll void open() {
        context = CodexJpaFixture.open(Services.class);
        decisions = context.getBean(BuildDecisions.class);
        sessions = context.getBean(RequirementSessionRepository.class);
        connections = context.getBean(SupabaseConnectionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }
    @AfterAll void close() { if (context != null) context.close(); }

    @Test void approvedChoiceRoundTripsThroughTheAdditiveSchema() {
        UUID project = UUID.randomUUID();
        var choice = decisions.approve(BuildDecisionsTest.answers("MERN", "ACCOUNTS"), decisions.available(project));
        approved(project, choice);
        assertEquals(choice, decisions.state(project).choice());
        assertFalse(decisions.state(project).blocked());
        assertEquals(1, jdbc.queryForObject("select count(*) from flyway_schema_history where version='27' and success", Integer.class));
    }

    @Test void connectionProjectionExcludesKeysAndAReplacementInvalidatesTheChoice() {
        UUID project = UUID.randomUUID();
        connections.saveAndFlush(SupabaseConnection.builder().projectId(project).projectRef("fixture-one")
                .url("https://fixture.invalid").anonKey("dummy-not-a-real-key").connectedBy(7L).build());
        var available = decisions.available(project);
        assertTrue(available.supabaseConnected());
        assertEquals(64, available.supabaseRevision().length());
        var choice = decisions.approve(BuildDecisionsTest.answers("SUPABASE", "NONE"), available);
        approved(project, choice);
        assertFalse(decisions.state(project).blocked());
        String prompt = decisions.state(project).prompt();
        assertFalse(prompt.contains("fixture-one") || prompt.contains("fixture.invalid") || prompt.contains("dummy-not"));
        jdbc.update("update project_supabase_connections set project_ref='fixture-two',updated_at=clock_timestamp() where project_id=?", project);
        assertTrue(decisions.state(project).blocked());
        connections.deleteById(project);
        assertTrue(decisions.state(project).blocked());
    }

    @Test void legacyApprovalIsPreservedButCannotBeMisinterpretedAsBrowserStorage() {
        UUID project = UUID.randomUUID();
        var session = sessions.saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("fixture")
                .status(RequirementStatus.APPROVED).build());
        assertTrue(decisions.state(project).blocked());
        assertNull(sessions.findById(session.getId()).orElseThrow().getBuildDecisionJson());
        assertEquals(RequirementStatus.APPROVED, sessions.findById(session.getId()).orElseThrow().getStatus());
    }

    @Configuration @Import(BuildDecisions.class)
    static class Services {
        @Bean AgentProperties properties() { var p = new AgentProperties(); p.getSandbox().setRequired(true); return p; }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
    }

    private void approved(UUID project, BuildDecisions.Choice choice) {
        var session = sessions.saveAndFlush(RequirementSession.builder().projectId(project).sourcePrompt("fixture")
                .status(RequirementStatus.APPROVED).approvedRevision(0L).buildDecisionJson(decisions.encode(choice)).build());
        UUID projection = UUID.randomUUID();
        // This fixture isolates decision persistence. Projection/file/outbox verification is RequirementCoordinationIT.
        jdbc.update("insert into requirement_projections(id,session_id,project_id,revision,payload_json,state) values (?,?,?,0,'{}','READY')", projection, session.getId(), project);
        jdbc.update("update requirement_sessions set projection_id=? where id=?", projection, session.getId());
    }
}
