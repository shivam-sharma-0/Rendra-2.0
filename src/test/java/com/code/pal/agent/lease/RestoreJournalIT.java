package com.code.pal.agent.lease;
import com.code.pal.agent.change.RestoreJournal;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreJournalIT {
    AnnotationConfigApplicationContext app; JdbcTemplate jdbc; ProjectLeaseService leases; RestoreJournal journal;
    @BeforeAll void open() {app=CodexJpaFixture.open(ProjectLeaseIT.Services.class);jdbc=app.getBean(JdbcTemplate.class);leases=app.getBean(ProjectLeaseService.class);journal=new RestoreJournal(jdbc,app.getBean(PlatformTransactionManager.class));}
    @AfterAll void close(){if(app!=null)app.close();}
    @Test void durablePendingRestoreBlocksTakeoverAndTrustedWritesAcrossRestart() {
        UUID project=UUID.randomUUID(),run=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Disposable restore','BUILD',false,'NONE',now(),now(),now())",project,UUID.randomUUID());
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,?,7,'BUILD','DONE',0,0,now(),now())",run,project,run.toString());
        leases.manualRestore(project,()->{journal.prepare(id,run,project,".codepal/restore/"+id);journal.state(id,"APPLYING");});
        assertTrue(leases.hasPendingRestore(project)); assertNull(leases.claim(project,run,UUID.randomUUID()));
        assertThrows(IllegalStateException.class,()->leases.guarded(project,()->{}));
        var reopened=new RestoreJournal(jdbc,app.getBean(PlatformTransactionManager.class));
        assertEquals("APPLYING",reopened.readState(id));reopened.state(id,"ROLLED_BACK");
        assertFalse(leases.hasPendingRestore(project));var fence=leases.claim(project,run,UUID.randomUUID());assertNotNull(fence);leases.release(fence);
    }
    @Test void restoreCannotStartUnderALiveWorkerLease() {
        UUID project=UUID.randomUUID(),run=UUID.randomUUID();var fence=leases.claim(project,run,UUID.randomUUID());assertNotNull(fence);
        assertThrows(IllegalStateException.class,()->leases.manualRestore(project,()->fail("Live restore started")));leases.release(fence);
    }
}
