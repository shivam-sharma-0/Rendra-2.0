package com.code.pal.agent.lease;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.runtime.RunCancellation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import java.util.function.Supplier;

/** DB-clock lease and a project critical section shared by takeover, manual writes and promotion.
 * Never holds the lease row lock over filesystem/command I/O: renewal can continue independently.
 */
@Service
public class ProjectLeaseService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AgentProperties props;
    private final ProjectMutationLocks locks;
    private final ThreadLocal<UUID> critical=new ThreadLocal<>();
    private final ThreadLocal<Boolean> recovery=new ThreadLocal<>();
    private final ThreadLocal<Boolean> restoring=new ThreadLocal<>();
    public ProjectLeaseService(JdbcTemplate jdbc, PlatformTransactionManager manager, AgentProperties props,ProjectMutationLocks locks) {
        this.jdbc=jdbc;this.props=props;this.locks=locks;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private void lock(UUID project) {
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 7341))",Object.class,project.toString());
    }
    public RunFence claim(UUID project, UUID run, UUID owner) {
        try(var filesystem=locks.acquire(project)) { return tx.execute(status->{
            lock(project);
            if(hasPendingIo(project) || hasPendingPromotion(project) || hasPendingRestore(project)) return null;
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from requirement_sessions s join requirement_projections p on p.id=s.projection_id where s.project_id=? and p.state='PENDING')",Boolean.class,project))) return null;
            // Interrupted promotion is recovered before another request can start changing this project.
            if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_runs where project_id=? and status='COMMITTING' and code_saved_at is null)",Boolean.class,project))) return null;
            var rows=jdbc.query("insert into agent_project_leases(project_id,epoch,run_id,owner_id,expires_at) "
                    +"values (?,1,?,?,clock_timestamp()+(? * interval '1 millisecond')) "
                    +"on conflict(project_id) do update set epoch=agent_project_leases.epoch+1,run_id=excluded.run_id,owner_id=excluded.owner_id,expires_at=excluded.expires_at "
                    +"where agent_project_leases.expires_at<=clock_timestamp() returning epoch",
                    (result,index)->result.getLong(1),project,run,owner,props.leaseMs());
            return rows.isEmpty()?null:new RunFence(project,run,owner,rows.getFirst());
        }); }
    }
    public boolean renew(RunFence fence) {
        return Boolean.TRUE.equals(tx.execute(status->{ int changed=jdbc.update("update agent_project_leases set expires_at=clock_timestamp()+(? * interval '1 millisecond') "
                +"where project_id=? and run_id=? and owner_id=? and epoch=? and expires_at>clock_timestamp()",
                props.leaseMs(),fence.projectId(),fence.runId(),fence.ownerId(),fence.epoch());
            if(changed==1) jdbc.update("update agent_runs set heartbeat_at=clock_timestamp(),updated_at=clock_timestamp() where id=? and status in ('RUNNING','COMMITTING')",fence.runId());
            return changed==1; }));
    }
    public boolean hasLiveLease(UUID project) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_project_leases where project_id=? and expires_at>clock_timestamp())",Boolean.class,project));
    }
    public void release(RunFence fence) {
        tx.executeWithoutResult(status->jdbc.update("update agent_project_leases set expires_at=clock_timestamp() "
                +"where project_id=? and run_id=? and owner_id=? and epoch=?",fence.projectId(),fence.runId(),fence.ownerId(),fence.epoch()));
    }
    public void requireCurrent(UUID project) {
        requireSafeIo(project);
        RunFence fence=RunFence.current();
        // Work with a run signal must carry the internal capability even on background threads.
        if(fence==null) { if(RunCancellation.current()!=RunCancellation.NONE || com.code.pal.agent.llm.LlmCallContext.current().runId()!=null) throw new LeaseLostException();return; }
        if(!project.equals(fence.projectId()) || !Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from agent_project_leases where project_id=? and run_id=? and owner_id=? and epoch=? and expires_at>clock_timestamp())",
                Boolean.class,project,fence.runId(),fence.ownerId(),fence.epoch()))) throw new LeaseLostException();
    }
    public java.util.List<UUID> pendingIoProjects() {
        return jdbc.query("select distinct project_id from agent_project_io where finished_at is null limit 100",(r,n)->r.getObject(1,UUID.class));
    }
    public void requireRecovery(UUID project) {
        if(!Boolean.TRUE.equals(recovery.get()) || !project.equals(critical.get()) || hasLiveLease(project))
            throw new IllegalStateException("Pending operations require inactive project recovery ownership");
    }
    public boolean hasPendingIo(UUID project) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_project_io where project_id=? and finished_at is null)",Boolean.class,project));
    }
    public java.util.List<UUID> pendingPromotionRuns() {
        return jdbc.query("select run_id from agent_promotions where state not in ('SAVED','ROLLED_BACK') order by created_at limit 100",(r,n)->r.getObject(1,UUID.class));
    }
    public boolean hasPendingPromotion(UUID project) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_promotions where project_id=? and state not in ('SAVED','ROLLED_BACK'))",Boolean.class,project));
    }
    private void requireSafeIo(UUID project) {
        if (!Boolean.TRUE.equals(restoring.get()) && hasPendingRestore(project))
            throw new IllegalStateException("Code restore requires recovery; project retained");
        if(!Boolean.TRUE.equals(recovery.get()) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from agent_runs r where r.project_id=? and r.status='COMMITTING' and r.code_saved_at is null and not exists(select 1 from agent_promotions p where p.run_id=r.id))",Boolean.class,project)))
            throw new com.code.pal.agent.change.PromotionRecoveryPendingException(null);
        if(!Boolean.TRUE.equals(recovery.get()) && hasPendingPromotion(project)) {
            RunFence fence=RunFence.current();
            if(fence==null || !Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_promotions where project_id=? and run_id=? and owner_id=? and lease_epoch=? and state not in ('SAVED','ROLLED_BACK'))",Boolean.class,
                    project,fence.runId(),fence.ownerId(),fence.epoch()))) throw new com.code.pal.agent.change.PromotionRecoveryPendingException(null);
        }
        if(!Boolean.TRUE.equals(recovery.get()) && hasPendingIo(project)) throw new TrustedOperationPendingException();
    }
    public void recovering(UUID project,Runnable action) {
        if(recovery.get()!=null) throw new IllegalStateException("Nested recovery");
        recovery.set(true);try{guarded(project,action);}finally{recovery.remove();}
    }
    public <T> T guarded(UUID project, Supplier<T> action) {
        if(project.equals(critical.get())) { requireCurrent(project);requireSafeIo(project);return action.get(); }
        if(critical.get()!=null) throw new IllegalStateException("Nested mutation targets another project");
        try(var filesystem=locks.acquire(project)) { return tx.execute(status->{lock(project);requireCurrent(project);requireSafeIo(project);critical.set(project);
            try { return action.get(); } finally { critical.remove(); }
        }); }
    }
    public void guarded(UUID project,Runnable action) { guarded(project,()->{action.run();return null;}); }
    /** Serialize a short preflight with trusted copies; never hold this across HTTP/install/build. */
    public void checkCurrent(UUID project){guarded(project,()->{});}
    public boolean hasPendingRestore(UUID project) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_code_restores where project_id=? and state not in ('RESTORED','ROLLED_BACK'))",Boolean.class,project));
    }
    public void manualRestore(UUID project,Runnable action) {
        guarded(project,()->{
            if(hasLiveLease(project)) throw new IllegalStateException("A live build owns this project");
            restoring.set(true);try{action.run();}finally{restoring.remove();}
        });
    }

    /** Only inactive recovery may enter a quarantined restore; ordinary writes remain blocked. */
    public void recoveringRestore(UUID project, Runnable action) {
        if (restoring.get() != null || recovery.get() != null) {
            throw new IllegalStateException("Nested restore recovery");
        }
        restoring.set(true);
        try {
            recovering(project, () -> {
                requireRecovery(project);
                if (hasPendingPromotion(project)) {
                    throw new IllegalStateException("Restore waits for pending project operations");
                }
                action.run();
            });
        } finally {
            restoring.remove();
        }
    }
}
