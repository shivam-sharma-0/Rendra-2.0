package com.code.pal.agent.contract;

import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.tool.AgentContext;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.config.AgentProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import com.code.pal.agent.starter.Starter;

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
        leases.guarded(run.getProjectId(), () -> tx.executeWithoutResult(s -> jdbc.update(
                "update agent_unit_outputs set state='VERIFIED' where id in (select distinct on(unit_id) id from agent_unit_outputs where run_id=? order by unit_id,created_at desc,id desc)",
                run.getId())));
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
        for (String id : units) {
            var entry = latest(run.getId(), id);
            if (!entry.contract().equals(stored.contract().id()) || !entry.request().equals(run.getRequestFingerprint())
                    || stored.contract().units().stream().noneMatch(u -> u.id().equals(id)))
                throw new IllegalStateException("Unit recovery identity failed");
            decode(entry);
        }
        return true;
    }
}
