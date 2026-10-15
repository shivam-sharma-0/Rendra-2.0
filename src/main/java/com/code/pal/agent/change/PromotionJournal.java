package com.code.pal.agent.change;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.workspace.ProjectPaths;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.ProjectLeaseService;
import java.sql.Timestamp;

/** Short independent transactions: filesystem critical sections must not roll this evidence back. */
@Service
public class PromotionJournal {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProjectLeaseService leases;
    public PromotionJournal(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper,ProjectLeaseService leases) {
        this.jdbc=jdbc;this.mapper=mapper;this.leases=leases;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }
    public record FileState(String path,String before,String after) {}
    public record Entry(UUID runId,UUID projectId,UUID ownerId,long epoch,int revision,
                        List<FileState> files,String state,Instant savedAt) {
        public String archive() { return ".codepal/history/"+runId+"/original"; }
        public boolean terminal() { return state.equals("SAVED") || state.equals("ROLLED_BACK"); }
    }
    public boolean prepare(AgentRun run,int revision,List<FileState> files) {
        validate(files);
        RunFence fence=RunFence.current();
        if(fence==null) throw new LeaseLostException();
        leases.requireCurrent(run.getProjectId());
        if( (!fence.runId().equals(run.getId()) || !fence.projectId().equals(run.getProjectId())))
            throw new IllegalArgumentException("Promotion ownership mismatch");
        return Boolean.TRUE.equals(tx.execute(status->{
            if(jdbc.update("update agent_runs set status='COMMITTING',commit_started_at=clock_timestamp(),updated_at=clock_timestamp() "
                    +"where id=? and project_id=? and status='RUNNING' and cancel_requested_at is null",run.getId(),run.getProjectId())!=1) return false;
            jdbc.update("insert into agent_promotions(run_id,project_id,owner_id,lease_epoch,revision,files_json,state) values (?,?,?,?,?,?,'PREPARING')",
                    run.getId(),run.getProjectId(),fence==null?null:fence.ownerId(),fence==null?0:fence.epoch(),revision,mapper.writeValueAsString(files));
            return true;
        }));
    }
    public Entry read(UUID run) {
        return tx.execute(status->jdbc.query("select * from agent_promotions where run_id=?",(r,n)->{
            List<FileState> files=Arrays.asList(mapper.readValue(r.getString("files_json"),FileState[].class));validate(files);
            var saved=r.getTimestamp("saved_at");
            return new Entry(run,r.getObject("project_id",UUID.class),r.getObject("owner_id",UUID.class),r.getLong("lease_epoch"),
                    r.getInt("revision"),List.copyOf(files),r.getString("state"),saved==null?null:saved.toInstant());
        },run).stream().findFirst().orElse(null));
    }
    public void ready(UUID run) {
        tx.executeWithoutResult(status->{
            transitionOwned(run,"PREPARING","READY");
            if(jdbc.update("update agent_runs set snapshot_ref=? where id=? and status='COMMITTING' and code_saved_at is null",
                    ChangeSetService.SNAPSHOT_PREFIX+run,run)!=1) throw new IllegalStateException("Restore point refused");
        });
    }
    public void applying(UUID run) { tx.executeWithoutResult(status->transitionOwned(run,"READY","APPLYING")); }
    public Instant saved(UUID run) {
        return tx.execute(status->{
            transitionOwned(run,"APPLYING","SAVED",true);
            Instant saved=jdbc.queryForObject("select saved_at from agent_promotions where run_id=?",Timestamp.class,run).toInstant();
            if(jdbc.update("update agent_runs set code_saved_at=?,preview_status='NOT_REQUESTED',save_recovery_required=false where id=? and status='COMMITTING' and code_saved_at is null",
                    Timestamp.from(saved),run)!=1) throw new IllegalStateException("Save finalization refused");
            return saved;
        });
    }
    public void recoveryState(UUID run,String state) {
        if(!Set.of("ROLLING_BACK","ROLLED_BACK","RECOVERY_REQUIRED").contains(state)) throw new IllegalArgumentException("Invalid recovery state");
        tx.executeWithoutResult(status->{
            UUID project=jdbc.queryForObject("select project_id from agent_promotions where run_id=?",UUID.class,run);
            if(RunFence.current()==null) leases.requireRecovery(project);else leases.requireCurrent(project);
            if(jdbc.update("update agent_promotions set state=?,updated_at=clock_timestamp() where run_id=? and state not in ('SAVED','ROLLED_BACK')",state,run)!=1)
                throw new IllegalStateException("Promotion recovery transition refused");
            jdbc.update("update agent_runs set save_recovery_required=? where id=? and code_saved_at is null",state.equals("RECOVERY_REQUIRED"),run);
        });
    }
    private void transitionOwned(UUID run,String before,String after) { transitionOwned(run,before,after,false); }
    private void transitionOwned(UUID run,String before,String after,boolean saved) {
        RunFence fence=RunFence.current();
        if(jdbc.update("update agent_promotions set state=?,updated_at=clock_timestamp()"+(saved?",saved_at=clock_timestamp()":"")
                +" where run_id=? and state=? and lease_epoch=? and owner_id is not distinct from ?"
                +" and exists(select 1 from agent_project_leases l where l.project_id=agent_promotions.project_id and l.run_id=agent_promotions.run_id"
                +" and l.owner_id=agent_promotions.owner_id and l.epoch=agent_promotions.lease_epoch and l.expires_at>clock_timestamp())",
                after,run,before,fence==null?0:fence.epoch(),fence==null?null:fence.ownerId())!=1)
            throw new IllegalStateException("Promotion transition refused");
    }
    public static void validate(List<FileState> files) {
        if(files==null || files.isEmpty() || files.size()>10_000) throw new IllegalArgumentException("Invalid promotion manifest");
        Set<String> seen=new HashSet<>();
        for(var file:files) {
            if(file==null || !ProjectPaths.agentPath(file.path(),false).equals(file.path()) || !seen.add(file.path())
                    || !version(file.before()) || !version(file.after())) throw new IllegalArgumentException("Invalid promotion file proof");
        }
    }
    private static boolean version(String value) { return value!=null && (value.equals(FileVersions.ABSENT) || value.matches("sha256:[a-f0-9]{64}")); }
}
