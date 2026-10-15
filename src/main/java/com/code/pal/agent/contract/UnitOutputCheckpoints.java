package com.code.pal.agent.contract;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.starter.Starter;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/** Durable proposals are GENERATED; only actual backend behavior can mark VERIFIED. Both require fresh final validation. */
@Service
public final class UnitOutputCheckpoints {
    private record Entry(UUID contract, String request, String context, String json, String hash, String state) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ContractStore contracts;
    private final ProjectLeaseService leases;
    private final ProjectFiles files;
    private final AgentProperties props;

    public UnitOutputCheckpoints(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper,
            ContractStore contracts, ProjectLeaseService leases, ProjectFiles files, AgentProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.contracts = contracts;
        this.leases = leases;
        this.files = files;
        this.props = props;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(5);
    }

    private String context(AgentRun run, UnitGenerator.Input input) {
        var versions = new TreeMap<String, String>();
        input.snapshot().forEach((p, v) -> {
            if (!input.unit().ownedPaths().contains(p))
                versions.put(p, v.version());
        });
        return ContractStore.hash("unit-v1/" + Starter.TEMPLATE_VERSION + "/" + props.getMaxRepairAttempts() + "/"
                + props.getSandbox().isRequired() + "/" + props.getSandbox().isRuntimeCheck() + "/"
                + run.getRequestFingerprint() + "/" + input.contract().id() + "/" + input.unit().id() + "/" + versions);
    }

    private Entry latest(UUID run, String unit) {
        return jdbc.query(
                "select contract_id,request_fingerprint,context_hash,output_json,output_hash,state from agent_unit_outputs where run_id=? and unit_id=? order by created_at desc,id desc limit 1",
                (r, n) -> new Entry(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6)),
                run, unit).stream().findFirst().orElse(null);
    }

    private Entry first(UUID run, String unit) {
        return jdbc.query(
                "select contract_id,request_fingerprint,context_hash,output_json,output_hash,state from agent_unit_outputs where run_id=? and unit_id=? order by created_at,id limit 1",
                (r, n) -> new Entry(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), r.getString(6)),
                run, unit).stream().findFirst().orElse(null);
    }

    private UnitGenerator.Output decode(Entry entry) {
        if (!ContractStore.hash(entry.json()).equals(entry.hash()))
            throw new IllegalStateException("Unit checkpoint integrity failed");
        return mapper.readValue(entry.json(), UnitGenerator.Output.class);
    }

    public void save(AgentRun run, UnitGenerator.Input input, UnitGenerator.Output output) {
        if (input.contract().schemaVersion() == 2) {
            throw new IllegalArgumentException("V2 output needs a complete batch receipt");
        }
        if (run.getRequestFingerprint() == null)
            return;
        if (!input.contract().units().contains(input.unit()))
            throw new IllegalArgumentException("Unit is outside the reviewed contract");
        UnitGenerator.validate(input, output);
        String json = mapper.writeValueAsString(output);
        if (json.length() > 512000)
            throw new IllegalArgumentException("Unit checkpoint exceeds capacity");
        leases.guarded(run.getProjectId(), () -> {
            if (jdbc.queryForObject(
                    "select count(*) from agent_runs where id=? and project_id=? and user_id=? and request_fingerprint=? and mode='BUILD' and status='RUNNING' and cancel_requested_at is null",
                    Integer.class, run.getId(), run.getProjectId(), run.getUserId(), run.getRequestFingerprint()) != 1)
                throw new IllegalStateException("Unit checkpoint accepted identity changed");
            var stored = contracts.forRun(run.getProjectId(), run.getId());
            if (stored == null || !stored.contract().equals(input.contract()) || !"APPROVED".equals(stored.state()))
                throw new IllegalStateException("Unit checkpoint contract binding unavailable");
            tx.executeWithoutResult(s -> jdbc.update(
                    "insert into agent_unit_outputs(id,run_id,contract_id,unit_id,request_fingerprint,context_hash,output_json,output_hash,state) values (?,?,?,?,?,?,?,?,'GENERATED')",
                    UUID.randomUUID(), run.getId(), input.contract().id(), input.unit().id(),
                    run.getRequestFingerprint(), context(run, input), json, ContractStore.hash(json)));
        });
    }

    public UnitGenerator.Output reuse(AgentRun run, UnitGenerator.Input input, AgentContext ctx) {
        if (input.contract().schemaVersion() == 2) {
            throw new IllegalArgumentException("V2 recovery needs its complete batch receipt");
        }
        if (!run.getProjectId().equals(ctx.projectId()) || !run.getId().equals(ctx.runId())
                || !Objects.equals(run.getUserId(), ctx.userId()) || !input.contract().units().contains(input.unit()))
            throw new IllegalStateException("Unit reuse identity changed");
        var row = latest(run.getId(), input.unit().id());
        if (row == null)
            return null;
        var stored = contracts.forRun(run.getProjectId(), run.getId());
        if (stored == null || !"APPROVED".equals(stored.state()) || !stored.contract().equals(input.contract())
                || !stored.binding().equals(contracts.current(run.getProjectId())))
            throw new IllegalStateException("Unit reuse approval changed");
        if (!row.contract().equals(input.contract().id()) || !Objects.equals(row.request(), run.getRequestFingerprint())
                || !row.context().equals(context(run, input)))
            return null;
        var output = decode(row);
        var original = new HashMap<>(input.snapshot());
        output.files().forEach(f -> original.put(f.path(), new UnitGenerator.FileView(f.expectedVersion(), null)));
        UnitGenerator.validate(new UnitGenerator.Input(input.contract(), input.unit(), input.request(),
                input.approvedRequirements(), original, ""), output);
        var initial = decode(first(run.getId(), input.unit().id()));
        var baselines = new HashMap<String, String>();
        initial.files().forEach(f -> baselines.put(f.path(), f.expectedVersion()));
        var needed = new ArrayList<UnitGenerator.Delta>();
        for (var f : output.files()) {
            String actual = files.version(ctx.projectId(), ctx.root(), f.path()),
                    after = f.content() == null ? "absent" : "sha256:" + ContractStore.hash(f.content());
            if (actual.equals(after))
                continue;
            if (!actual.equals(f.expectedVersion()) && !actual.equals(baselines.get(f.path())))
                throw new IllegalStateException("Unit checkpoint target changed");
            needed.add(new UnitGenerator.Delta(f.path(), actual, f.content()));
        }
        return new UnitGenerator.Output(needed);
    }

    public void verified(AgentRun run) {
        if (run.getRequestFingerprint() == null)
            return;
        leases.guarded(run.getProjectId(), () -> {
            requireRunning(run);
            tx.executeWithoutResult(s -> jdbc.update(
                "update agent_unit_outputs set state='VERIFIED' where id in (select distinct on(unit_id) id from agent_unit_outputs where run_id=? order by unit_id,created_at desc,id desc)",
                run.getId()));
        });
    }

    public boolean canResume(AgentRun run) {
        if (run.getRequestFingerprint() == null)
            return false;
        var stored = contracts.forRun(run.getProjectId(), run.getId());
        if (stored == null || !"APPROVED".equals(stored.state())
                || !stored.binding().equals(contracts.current(run.getProjectId())))
            return false;
        var units = jdbc.query("select distinct unit_id from agent_unit_outputs where run_id=?",
                (r, n) -> r.getString(1), run.getId());
        if (units.isEmpty())
            return false;
        if (stored.contract().schemaVersion() == 2) {
            for (String key : units) {
                var batch = stored.contract().batches().stream().filter(value -> BatchReceipts.key(value).equals(key)).findFirst().orElse(null);
                if (batch == null) {
                    return false; // An old per-unit v2 row cannot prove an atomic batch.
                }
                var entry = latest(run.getId(), key);
                if (!entry.contract().equals(stored.contract().id()) || !Objects.equals(entry.request(), run.getRequestFingerprint())) {
                    return false;
                }
                BatchReceipts.validate(stored.contract(), batch, decodeBatch(entry));
            }
            return true;
        }
        for (String id : units) {
            var entry = latest(run.getId(), id);
            if (!entry.contract().equals(stored.contract().id()) || !entry.request().equals(run.getRequestFingerprint())
                    || stored.contract().units().stream().noneMatch(u -> u.id().equals(id)))
                throw new IllegalStateException("Unit recovery identity failed");
            decode(entry);
        }
        return true;
    }

    public void saveBatch(AgentRun run, List<UnitGenerator.Input> inputs, List<UnitGenerator.Output> outputs) {
        var batch = BatchReceipts.batch(inputs);
        var contract = inputs.getFirst().contract();
        if (outputs.size() != inputs.size()) {
            throw new IllegalArgumentException("Incomplete batch output");
        }
        var units = new ArrayList<BatchUnitGenerator.UnitOutput>();
        for (int index = 0; index < inputs.size(); index++) {
            units.add(new BatchUnitGenerator.UnitOutput(inputs.get(index).unit().id(), outputs.get(index).files()));
        }
        var receipt = new BatchUnitGenerator.Output(units);
        BatchReceipts.validate(contract, batch, receipt);
        for (int index = 0; index < inputs.size(); index++) {
            if (!outputs.get(index).files().isEmpty()) {
                UnitGenerator.validate(inputs.get(index), outputs.get(index));
            }
        }
        if (run.getRequestFingerprint() == null) {
            return;
        }
        String json = mapper.writeValueAsString(receipt);
        if (json.length() > 512_000) {
            throw new IllegalArgumentException("Batch checkpoint exceeds capacity");
        }
        leases.guarded(run.getProjectId(), () -> {
            requireRunning(run);
            requireContract(run, contract);
            tx.executeWithoutResult(status -> jdbc.update(
                    "insert into agent_unit_outputs(id,run_id,contract_id,unit_id,request_fingerprint,context_hash,output_json,output_hash,state) values (?,?,?,?,?,?,?,?,'GENERATED')",
                    UUID.randomUUID(), run.getId(), contract.id(), BatchReceipts.key(batch), run.getRequestFingerprint(),
                    BatchReceipts.context(run, inputs, props, mapper), json, ContractStore.hash(json)));
        });
    }

    public List<UnitGenerator.Output> reuseBatch(AgentRun run, List<UnitGenerator.Input> inputs, AgentContext ctx) {
        var batch = BatchReceipts.batch(inputs);
        if (!run.getProjectId().equals(ctx.projectId()) || !run.getId().equals(ctx.runId()) || !Objects.equals(run.getUserId(), ctx.userId())) {
            throw new IllegalStateException("Batch reuse identity changed");
        }
        var contract = inputs.getFirst().contract();
        requireContract(run, contract);
        var entry = latest(run.getId(), BatchReceipts.key(batch));
        if (entry == null) {
            return List.of();
        }
        if (!entry.contract().equals(contract.id()) || !Objects.equals(entry.request(), run.getRequestFingerprint())
                || !entry.context().equals(BatchReceipts.context(run, inputs, props, mapper))) {
            return List.of();
        }
        var latest = BatchReceipts.validate(contract, batch, decodeBatch(entry));
        var original = BatchReceipts.validate(contract, batch, decodeBatch(first(run.getId(), BatchReceipts.key(batch))));
        return BatchReceipts.replay(latest, original, ctx, files);
    }

    private BatchUnitGenerator.Output decodeBatch(Entry entry) {
        if (entry == null || !ContractStore.hash(entry.json()).equals(entry.hash())) {
            throw new IllegalStateException("Batch checkpoint integrity failed");
        }
        return mapper.readValue(entry.json(), BatchUnitGenerator.Output.class);
    }

    private void requireRunning(AgentRun run) {
        if (jdbc.queryForObject("select count(*) from agent_runs where id=? and project_id=? and user_id=? and request_fingerprint=? "
                + "and mode='BUILD' and status='RUNNING' and cancel_requested_at is null", Integer.class,
                run.getId(), run.getProjectId(), run.getUserId(), run.getRequestFingerprint()) != 1) {
            throw new IllegalStateException("Checkpoint accepted identity changed or Stop was requested");
        }
    }

    private void requireContract(AgentRun run, BuildContract contract) {
        var stored = contracts.forRun(run.getProjectId(), run.getId());
        if (stored == null || !"APPROVED".equals(stored.state()) || !stored.contract().equals(contract)
                || !stored.binding().equals(contracts.current(run.getProjectId()))) {
            throw new IllegalStateException("Batch reuse approval changed");
        }
    }
}
