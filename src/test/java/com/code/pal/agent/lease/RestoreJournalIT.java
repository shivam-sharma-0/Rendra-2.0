package com.code.pal.agent.lease;
import com.code.pal.agent.change.RestoreJournal;
import com.code.pal.agent.change.RestoreRecoveryService;
import com.code.pal.agent.change.PromotionJournal;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.agent.workspace.FencedSandboxPort;
import tools.jackson.databind.ObjectMapper;
import com.code.pal.agent.AgentEvents;
import com.code.pal.agent.AgentRunRecovery;
import com.code.pal.agent.change.ChangeSetService;
import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void preparingRestoreIsRecoveredByTheSweepAndAllowsAnotherRestore() {
        UUID project = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Interrupted restore','BUILD',false,'NONE',now(),now(),now())",
                project, UUID.randomUUID());
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,?,7,'BUILD','DONE',0,0,now(),now())",
                run, project, run.toString());
        leases.manualRestore(project, () -> journal.prepare(id, run, project, ".codepal/restore/" + id));
        assertTrue(leases.hasPendingRestore(project));

        SandboxPort sandbox = mock(SandboxPort.class);
        when(sandbox.reconcilePendingOperations(project)).thenReturn(true);
        SiteBuildService sites = mock(SiteBuildService.class);
        var restores = new RestoreRecoveryService(leases, journal, app.getBean(PromotionJournal.class),
                app.getBean(ProjectFiles.class), sandbox, sites);
        var recovery = new AgentRunRecovery(leases, restores, new AgentProperties(), mock(AgentRunRepository.class),
                mock(AgentJobQueue.class), mock(AgentEvents.class), mock(MessageRepository.class),
                mock(RealtimeService.class), sandbox, mock(ChangeSetService.class), sites);
        recovery.sweep();

        assertEquals("ROLLED_BACK", journal.readState(id));
        assertFalse(leases.hasPendingRestore(project));
        assertDoesNotThrow(() -> leases.manualRestore(project, () -> {}));
    }

    @Test
    void realFilesAreCompensatedAndManualRetryIsUnlocked() {
        UUID project = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Mixed restore','BUILD',false,'NONE',now(),now(),now())",
                project, UUID.randomUUID());
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) values (?,?,?,7,'BUILD','DONE',0,0,now(),now())",
                run, project, run.toString());
        app.getBean(WorkspaceStorage.class).ensure(project);
        ProjectFiles files = app.getBean(ProjectFiles.class);
        String backup = ".codepal/restore/" + id;
        String archive = ".codepal/history/" + run + "/original";
        var proof = new ArrayList<PromotionJournal.FileState>();
        for (String path : List.of("a.txt", "b.txt")) {
            files.writeText(project, archive, path, "old " + path);
            files.writeText(project, ".", path, "new " + path);
            files.copyFile(project, ".", path, backup, path);
            proof.add(new PromotionJournal.FileState(path, files.version(project, archive, path),
                    files.version(project, ".", path)));
        }
        jdbc.update("insert into agent_promotions(run_id,project_id,lease_epoch,revision,files_json,state,saved_at) values (?,?,1,1,?,'SAVED',clock_timestamp())",
                run, project, app.getBean(ObjectMapper.class).writeValueAsString(proof));
        leases.manualRestore(project, () -> {
            journal.prepare(id, run, project, backup);
            journal.state(id, "APPLYING");
            files.copyFile(project, archive, "a.txt", ".", "a.txt");
        });
        assertThrows(IllegalStateException.class, () -> files.writeText(project, ".", "blocked.txt", "no"));

        new RestoreRecoveryService(leases, journal, app.getBean(PromotionJournal.class), files,
                app.getBean(FencedSandboxPort.class), mock(SiteBuildService.class))
                .recoverPending(System.nanoTime() + 5_000_000_000L);

        assertEquals("ROLLED_BACK", journal.readState(id));
        assertEquals("new a.txt", files.readText(project, ".", "a.txt"));
        assertEquals("new b.txt", files.readText(project, ".", "b.txt"));
        assertFalse(files.exists(project, backup, "a.txt"));
        assertDoesNotThrow(() -> leases.manualRestore(project, () -> {}));
    }

    @Test
    void restoreRecoveryCannotRunUnderALiveLeaseAndClearsItsScopedPrivilege() {
        UUID project = UUID.randomUUID();
        var owner = leases.claim(project, UUID.randomUUID(), UUID.randomUUID());
        assertNotNull(owner);
        try {
            assertThrows(IllegalStateException.class,
                    () -> leases.recoveringRestore(project, () -> fail("Live restore recovery started")));
        } finally {
            leases.release(owner);
        }
        assertDoesNotThrow(() -> leases.manualRestore(project, () -> {}));
    }
}
