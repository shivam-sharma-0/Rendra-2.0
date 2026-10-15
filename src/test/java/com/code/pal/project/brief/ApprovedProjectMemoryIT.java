package com.code.pal.project.brief;

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

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApprovedProjectMemoryIT {
    private AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private ApprovedProjectMemory memory;
    private ObjectMapper mapper;
    @BeforeAll void open(){app=CodexJpaFixture.open(Services.class);jdbc=app.getBean(JdbcTemplate.class);memory=app.getBean(ApprovedProjectMemory.class);mapper=app.getBean(ObjectMapper.class);}
    @AfterAll void close(){if(app!=null)app.close();}
    private record Fixture(UUID project,UUID session,UUID projection){}
    private Fixture approval(long revision,String answer) {
        UUID project=UUID.randomUUID();
        var session=app.getBean(RequirementSessionRepository.class).saveAndFlush(RequirementSession.builder().projectId(project)
                .sourcePrompt("disposable approved memory").status(RequirementStatus.APPROVED).answerRevision(revision).approvedRevision(revision).build());
        UUID projection=projection(project,session.getId(),revision,answer);
        jdbc.update("update requirement_sessions set projection_id=? where id=?",projection,session.getId());
        return new Fixture(project,session.getId(),projection);
    }
    private UUID projection(UUID project,UUID session,long revision,String answer) {
        UUID id=UUID.randomUUID();String requirements=mapper.writeValueAsString(Map.of("revision",revision,"answers",List.of(Map.of("question","Theme","answer",answer))));
        jdbc.update("insert into requirement_projections(id,session_id,project_id,revision,payload_json,state) values (?,?,?,?,?,'READY')",
                id,session,project,revision,mapper.writeValueAsString(new RequirementProjection.Payload(requirements,"# Theme\n"+answer)));
        return id;
    }
    @Test void correctionUsesCurrentCommittedRevisionAndOldPayloadRemainsImmutable() {
        var fixture=approval(2,"Old blue");String old=memory.current(fixture.project()).requirementsJson();
        UUID next=projection(fixture.project(),fixture.session(),3,"New purple");
        jdbc.update("update requirement_sessions set answer_revision=3,approved_revision=3,projection_id=? where id=?",next,fixture.session());
        var restarted=new ApprovedProjectMemory(jdbc,new ObjectMapper());
        assertEquals(3,restarted.current(fixture.project()).revision());assertTrue(restarted.prompt(fixture.project()).contains("New purple"));
        assertFalse(restarted.prompt(fixture.project()).contains("Old blue"));
        String historical=jdbc.queryForObject("select payload_json from requirement_projections where id=?",String.class,fixture.projection());
        assertEquals(old,mapper.readValue(historical,RequirementProjection.Payload.class).requirementsJson());
        assertThrows(IllegalArgumentException.class,()->memory.answer(fixture.project(),0,1,10,2L));
    }
    @Test void pendingAndStaleApprovalsCannotSupplyAuthoritativeMemory() {
        var fixture=approval(2,"Approved");
        jdbc.update("update requirement_projections set state='PENDING' where id=?",fixture.projection());
        assertNull(memory.current(fixture.project()));
        jdbc.update("update requirement_projections set state='READY' where id=?",fixture.projection());
        jdbc.update("update requirement_sessions set answer_revision=3 where id=?",fixture.session());
        assertNull(memory.current(fixture.project()));
    }
    @Test void anotherProjectsProjectionNeverLeaksThroughAnIncorrectPointer() {
        var first=approval(1,"First tenant");var other=approval(1,"Other tenant private");
        jdbc.update("update requirement_sessions set projection_id=? where id=?",other.projection(),first.session());
        assertNull(memory.current(first.project()));assertEquals("",memory.prompt(first.project()));
        assertThrows(IllegalArgumentException.class,()->memory.answer(first.project(),0,0,4000,null));
    }
    @Configuration @Import(ApprovedProjectMemory.class)
    static class Services {@Bean ObjectMapper mapper(){return new ObjectMapper();}}
}
