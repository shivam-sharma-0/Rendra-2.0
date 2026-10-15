package com.code.pal.agent.change;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import com.code.pal.project.event.ProjectDeleted;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Repository
public class FailedDraftRepository {
    public record Stored(UUID runId,UUID projectId,String manifest,byte[] archive,String hash,String state,UUID retryRunId) {}
    private final JdbcTemplate jdbc;
    public FailedDraftRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Stored find(UUID project,UUID run) {
        return jdbc.query("select run_id,project_id,manifest_json,archive,archive_hash,state,retry_run_id from agent_failed_drafts where run_id=? and project_id=?",
                (r,n)->new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getBytes(4),r.getString(5),r.getString(6),r.getObject(7,UUID.class)),run,project)
                .stream().findFirst().orElse(null);
    }
    @Transactional
    public void retain(Stored draft) {
        lock(draft.projectId());
        // A concurrent soft delete must commit after retention, so its cleanup cannot miss a late insert.
        if(jdbc.query("select id from projects where id=? and deleted_at is null for share",(r,n)->r.getObject(1,UUID.class),draft.projectId()).isEmpty())
            throw new IllegalStateException("Deleted project cannot retain drafts");
        int inserted=jdbc.update("insert into agent_failed_drafts(run_id,project_id,manifest_json,archive,archive_hash,state) "
                +"select id,project_id,?,?,?,? from agent_runs where id=? and project_id=? and mode='BUILD' "
                +"and status in ('FAILED','CANCELLED') and code_saved_at is null and save_recovery_required=false "
                +"on conflict(run_id) do nothing",draft.manifest(),draft.archive(),draft.hash(),draft.state(),draft.runId(),draft.projectId());
        if(inserted==0 && find(draft.projectId(),draft.runId())==null) throw new IllegalStateException("Failed draft retention refused");
        // Bound durable source storage to five artifacts per project. Expired metadata stays discoverable.
        jdbc.update("update agent_failed_drafts set archive=null,archive_hash=null,state='EXPIRED' where run_id in "
                +"(select d.run_id from agent_failed_drafts d where d.project_id=? and d.state='AVAILABLE' "
                +"and not exists(select 1 from agent_runs r where r.retry_source_run_id=d.run_id and r.status in ('QUEUED','RUNNING','COMMITTING')) "
                +"order by d.created_at desc,d.run_id desc offset 5)",draft.projectId());
    }
    public void lock(UUID project) {
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 9371))",Object.class,project.toString());
    }
    public void markRetry(UUID project,UUID source,UUID retry) {
        if(jdbc.update("update agent_failed_drafts set retry_run_id=? where project_id=? and run_id=? and state='AVAILABLE' and retry_run_id is null",retry,project,source)!=1)
            throw new IllegalStateException("Draft retry acceptance refused");
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void onDeleted(ProjectDeleted event) {jdbc.update("delete from agent_failed_drafts where project_id=?",event.projectId());}

    @Scheduled(fixedDelayString="${app.agent.deleted-project-sweep-ms:60000}")
    @Transactional
    public void purgeDeleted() {
        jdbc.update("delete from agent_failed_drafts where project_id in (select d.project_id from agent_failed_drafts d "
                +"join projects p on p.id=d.project_id where p.deleted_at is not null group by d.project_id limit 100)");
    }
}
