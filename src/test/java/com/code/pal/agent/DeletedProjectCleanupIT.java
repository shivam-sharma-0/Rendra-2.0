package com.code.pal.agent;
import com.code.pal.testsupport.CodexJpaFixture;
import com.code.pal.agent.workspace.LocalProject;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.project.database.GeneratedDatabaseService;
import com.code.pal.project.service.ProjectService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="codepal.disposable.it",matches="true")
class DeletedProjectCleanupIT {
    @Test void durableTombstoneRetainsCleanupAfterFolderLossAndRestart() {
        try(var app=CodexJpaFixture.open(Services.class)) {
            var jdbc=app.getBean(JdbcTemplate.class);var jobs=app.getBean(DeletedProjectCleanupJobs.class);var project=LocalProject.create();project.write("index.html","fixture");
            UUID live=UUID.randomUUID();for(UUID id:List.of(project.projectId,live))jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) values (?,?,7,'Cleanup fixture','BUILD',false,'NONE',now(),now(),now())",id,UUID.randomUUID());
            jdbc.update("update projects set deleted_at=now() where id=?",project.projectId);assertTrue(jobs.pending().contains(project.projectId));assertFalse(jobs.pending().contains(live));
            var database=mock(GeneratedDatabaseService.class);doThrow(new IllegalStateException("Unavailable")).doNothing().when(database).dropAll(project.projectId);
            var worker=new DeletedProjectCleanup(mock(ProjectService.class),mock(AgentRunRepository.class),mock(AgentJobQueue.class),project.sandbox,project.storage,database);worker.useJobs(jobs);
            worker.sweep();assertFalse(project.exists("index.html"));assertTrue(jobs.pending().contains(project.projectId));
            var restarted=new DeletedProjectCleanupJobs(jdbc);worker.useJobs(restarted);worker.sweep();assertFalse(restarted.pending().contains(project.projectId));
            assertEquals(1,jdbc.queryForObject("select count(*) from projects where id=? and cleanup_completed_at is not null",Integer.class,project.projectId));
            restarted.completed(live);assertEquals(0,jdbc.queryForObject("select count(*) from projects where id=? and cleanup_completed_at is not null",Integer.class,live));
            verify(database,times(2)).dropAll(project.projectId);
        }
    }
    @Configuration @Import(DeletedProjectCleanupJobs.class) static class Services {}
}
