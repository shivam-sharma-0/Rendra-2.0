package com.code.pal.agent.change;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.plan.AgentPlanState;
import com.code.pal.agent.route.RunSize;
import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.code.pal.agent.contract.BuildContract;
import com.code.pal.agent.contract.UnitOutputCheckpoints;
import com.code.pal.agent.lease.LeaseLostException;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Autowired;

/** Immutable proved file outputs, plus counters/deadline that survive one bounded worker resume. */
@Service
public class SequentialCheckpoints {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProjectFiles files;
    private final ProjectLeaseService leases;
    private final AgentProperties props;
    private UnitOutputCheckpoints unitOutputs;

    @Autowired(required = false)
    public void useUnitOutputs(UnitOutputCheckpoints outputs) {
        this.unitOutputs = outputs;
    }

    public SequentialCheckpoints(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper,
            ProjectFiles files, ProjectLeaseService leases, AgentProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.files = files;
        this.leases = leases;
        this.props = props;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    public record Checkpoint(UUID id, UUID runId, UUID projectId, Long userId, String requestFingerprint,
            int revision, int repairAttempts, String basis, AgentPlanState.Snapshot plan,
            List<PromotionJournal.FileState> files) {
        public String root() {
            return ".codepal/checkpoints/" + runId + "/" + id;
        }
    }

    public record Progress(int iterations, int modelCalls, int toolCalls, int resumeCount, long remainingMs) {
    }

    private String basis() {
        // Format/config changes invalidate reuse; required final validation still runs on the new stage.
        return "sequential-v1/" + props.getMaxRepairAttempts() + "/" + props.getSandbox().isRequired();
    }

    public Progress configure(AgentRun run, RunSize size) {
        return leases.guarded(run.getProjectId(), () -> {
            var owner = RunFence.current();
            if (owner == null || !owner.runId().equals(run.getId()))
                throw new LeaseLostException();
            tx.executeWithoutResult(status -> jdbc.update(
                    "insert into agent_execution_progress(run_id,iteration_limit,model_call_limit,tool_call_limit,deadline_at) "
                            + "values (?,?,?,?,clock_timestamp()+(? * interval '1 millisecond')) on conflict(run_id) do update set "
                            + "iteration_limit=least(agent_execution_progress.iteration_limit,excluded.iteration_limit),"
                            + "model_call_limit=least(agent_execution_progress.model_call_limit,excluded.model_call_limit),"
                            + "tool_call_limit=least(agent_execution_progress.tool_call_limit,excluded.tool_call_limit)",
                    run.getId(), size.maxIterations(), size.maxModelCalls(), size.maxToolCalls(), size.timeoutMs()));
            return progress(run.getId());
        });
    }

    public Progress progress(UUID run) {
        return tx.execute(status -> jdbc.queryForObject("select iterations,model_calls,tool_calls,resume_count,"
                + "greatest(0,extract(epoch from(deadline_at-clock_timestamp()))*1000)::bigint remaining_ms from agent_execution_progress where run_id=?",
                (r, n) -> new Progress(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4), r.getLong(5)), run));
    }

    public boolean reserveModelIteration(AgentRun run) {
        return reserve(run, 0);
    }

    /**
     * Returns one reserved model/iteration slot for an attempt that provably produced no completion. The
     * refund row makes it idempotent across retries and restarts; tokens, cost, tools and time are never refunded.
     */
    public boolean refundModelIteration(AgentRun run, UUID logicalId, int ordinal) {
        return leases.guarded(run.getProjectId(), () -> {
            RunFence fence = RunFence.current();
            if (fence == null || !fence.runId().equals(run.getId()))
                throw new IllegalStateException("Execution progress requires run ownership");
            return Boolean.TRUE.equals(tx.execute(status -> {
                int inserted = jdbc.update("insert into agent_model_call_refunds(run_id,logical_id,ordinal) values (?,?,?) "
                        + "on conflict do nothing", run.getId(), logicalId, ordinal);
                if (inserted != 1)
                    return false;
                jdbc.update("update agent_execution_progress set iterations=greatest(iterations-1,0),"
                        + "model_calls=greatest(model_calls-1,0) where run_id=?", run.getId());
                return true;
            }));
        });
    }

    public void restrict(AgentRun run, BuildContract.Limits limits, RunSize original) {
        leases.guarded(run.getProjectId(), () -> {
            tx.executeWithoutResult(s -> jdbc.update(
                    "update agent_execution_progress set model_call_limit=least(model_call_limit,?),tool_call_limit=least(tool_call_limit,?),"
                            + "deadline_at=least(deadline_at,started_at+(? * interval '1 millisecond')) where run_id=?",
                    limits.modelCalls(), limits.toolCalls(), limits.timeoutMs(), run.getId()));
        });
    }

    public boolean reserveTools(AgentRun run, int count) {
        if (count < 1)
            throw new IllegalArgumentException("Invalid tool count");
        return reserve(run, count);
    }

    private boolean reserve(AgentRun run, int tools) {
        return leases.guarded(run.getProjectId(), () -> {
            RunFence fence = RunFence.current();
            if (fence == null || !fence.runId().equals(run.getId()))
                throw new IllegalStateException("Execution progress requires run ownership");
            return Boolean.TRUE.equals(tx.execute(status -> jdbc.update("update agent_execution_progress p set "
                    + (tools == 0 ? "iterations=iterations+1,model_calls=model_calls+1" : "tool_calls=tool_calls+?")
                    + " where run_id=? and deadline_at>clock_timestamp() and "
                    + (tools == 0 ? "iterations<iteration_limit and model_calls<model_call_limit"
                            : "tool_calls<=tool_call_limit-?")
                    + " and exists(select 1 from agent_runs r where r.id=p.run_id and r.status='RUNNING' and r.cancel_requested_at is null)"
                    + " and exists(select 1 from agent_project_leases l where l.project_id=? and l.run_id=p.run_id and l.owner_id=? and l.epoch=? and l.expires_at>clock_timestamp())",
                    tools == 0 ? new Object[] { run.getId(), run.getProjectId(), fence.ownerId(), fence.epoch() }
                            : new Object[] { tools, run.getId(), tools, run.getProjectId(), fence.ownerId(),
                                    fence.epoch() }) == 1));
        });
    }

    public Checkpoint latest(UUID run) {
        return tx.execute(status -> jdbc.query(
                "select id,checkpoint_json,manifest_sha256,revision from agent_checkpoints where run_id=? order by revision desc limit 1",
                (r, n) -> {
                    String json = r.getString(2);
                    if (!hash(json).equals(r.getString(3)))
                        throw new IllegalStateException("Checkpoint manifest proof failed");
                    var checkpoint = mapper.readValue(json, Checkpoint.class);
                    if (!checkpoint.id().equals(r.getObject(1, UUID.class)) || !checkpoint.runId().equals(run)
                            || checkpoint.revision() != r.getInt(4))
                        throw new IllegalStateException("Checkpoint identity proof failed");
                    PromotionJournal.validate(checkpoint.files());
                    return checkpoint;
                }, run).stream().findFirst().orElse(null));
    }

    public void save(AgentRun run, String stage, int revision, int repairs, AgentPlanState.Snapshot plan,
            List<PromotionJournal.FileState> proof) {
        if (run.getRequestFingerprint() == null)
            return; // Legacy requests have no immutable accepted identity.
        leases.guarded(run.getProjectId(), () -> {
            requireIdentity(run);
            PromotionJournal.validate(proof);
            var old = latest(run.getId());
            if (old != null && old.revision() == revision) {
                if (!old.files().equals(proof))
                    throw new IllegalStateException("A certified revision changed without a mutation record");
                verify(old, stage, false);
                return;
            }
            var checkpoint = new Checkpoint(UUID.randomUUID(), run.getId(), run.getProjectId(), run.getUserId(),
                    run.getRequestFingerprint(),
                    revision, repairs, basis(), plan, List.copyOf(proof));
            for (var file : proof)
                if (!file.after().equals(FileVersions.ABSENT))
                    files.copyFile(run.getProjectId(), stage, file.path(), checkpoint.root(), file.path());
            verify(checkpoint, checkpoint.root(), false);
            verify(checkpoint, stage, false);
            String json = mapper.writeValueAsString(checkpoint);
            tx.executeWithoutResult(status -> jdbc.update(
                    "insert into agent_checkpoints(id,run_id,revision,checkpoint_json,manifest_sha256) values (?,?,?,?,?)",
                    checkpoint.id(), run.getId(), revision, json, hash(json)));
        });
    }

    public Checkpoint restore(AgentRun run, String stage) {
        return leases.guarded(run.getProjectId(), () -> {
            var checkpoint = latest(run.getId());
            if (checkpoint == null)
                return null;
            requireIdentity(run);
            if (!checkpoint.projectId().equals(run.getProjectId()) || !checkpoint.userId().equals(run.getUserId())
                    || !checkpoint.requestFingerprint().equals(run.getRequestFingerprint())
                    || !checkpoint.basis().equals(basis()))
                throw new IllegalStateException("Checkpoint request/config basis changed");
            verify(checkpoint, ".", true);
            verify(checkpoint, checkpoint.root(), false);
            // Untouched context comes from the new accepted tree; only proved outputs are reused.
            for (var file : checkpoint.files()) {
                if (file.after().equals(FileVersions.ABSENT))
                    files.delete(run.getProjectId(), stage, file.path());
                else
                    files.copyFile(run.getProjectId(), checkpoint.root(), file.path(), stage, file.path());
            }
            verify(checkpoint, stage, false);
            return checkpoint;
        });
    }

    private void requireIdentity(AgentRun run) {
        var owner = RunFence.current();
        if (owner != null && !owner.runId().equals(run.getId()))
            throw new LeaseLostException();
        if (run.getRequestFingerprint() == null || !run.getRequestFingerprint().matches("[a-f0-9]{64}")
                || !Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from agent_runs where id=? and project_id=? and user_id=? and request_fingerprint=?)",
                        Boolean.class,
                        run.getId(), run.getProjectId(), run.getUserId(), run.getRequestFingerprint())))
            throw new IllegalStateException("Checkpoint accepted identity unavailable");
    }

    private void verify(Checkpoint checkpoint, String root, boolean before) {
        for (var file : checkpoint.files())
            if (!files.version(checkpoint.projectId(), root, file.path()).equals(before ? file.before() : file.after()))
                throw new IllegalStateException("Checkpoint file proof failed: " + file.path());
    }

    /** Inactive owned recovery only; status and outbox reset commit atomically even when Redis is down. */
    public boolean requeue(AgentRun run) {
        leases.requireRecovery(run.getProjectId());
        var checkpoint = latest(run.getId());
        boolean artifacts = unitOutputs != null && unitOutputs.canResume(run);
        if (checkpoint == null && !artifacts)
            return false;
        requireIdentity(run);
        if (checkpoint != null
                && (!checkpoint.projectId().equals(run.getProjectId()) || !checkpoint.userId().equals(run.getUserId())
                        || !checkpoint.requestFingerprint().equals(run.getRequestFingerprint())
                        || !checkpoint.basis().equals(basis())))
            throw new IllegalStateException("Checkpoint resume basis changed");
        if (checkpoint != null) {
            new AgentPlanState().restore(checkpoint.plan());
            verify(checkpoint, ".", true);
            verify(checkpoint, checkpoint.root(), false);
        }
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (jdbc.update(
                    "update agent_execution_progress p set resume_count=resume_count+1 where run_id=? and resume_count=0 "
                            + (artifacts ? "and iterations<=iteration_limit and model_calls<=model_call_limit "
                                    : "and iterations<iteration_limit and model_calls<model_call_limit ")
                            + "and tool_calls<tool_call_limit and deadline_at>clock_timestamp() "
                            + "and exists(select 1 from agent_runs r where r.id=p.run_id and r.status='RUNNING' and r.mode='BUILD' and r.cancel_requested_at is null and r.commit_started_at is null)",
                    run.getId()) != 1)
                return false;
            if (jdbc.update(
                    "update agent_runs set status='QUEUED',heartbeat_at=null,updated_at=clock_timestamp(),error=null where id=? and status='RUNNING' and cancel_requested_at is null",
                    run.getId()) != 1)
                throw new IllegalStateException("Checkpoint resume refused");
            jdbc.update(
                    "insert into agent_dispatch_outbox(run_id,created_at,dispatched_at) values (?,clock_timestamp(),null) "
                            + "on conflict(run_id) do update set dispatched_at=null",
                    run.getId());
            return true;
        }));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
