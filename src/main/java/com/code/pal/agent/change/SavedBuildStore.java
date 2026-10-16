package com.code.pal.agent.change;

import com.code.pal.agent.lease.LeaseLostException;
import com.code.pal.agent.lease.ProjectLeaseService;
import com.code.pal.agent.lease.RunFence;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.project.capability.BuildDecisions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** AFTER source versions are separate from promotion's BEFORE archives and the published LIVE pointer. */
@Service
public class SavedBuildStore {
    private static final String SAVED = " from agent_saved_builds s join agent_promotions p on p.run_id=s.run_id "
            + "join agent_runs r on r.id=s.run_id where s.project_id=? and p.project_id=s.project_id "
            + "and r.project_id=s.project_id and p.state='SAVED' and r.code_saved_at is not null ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final ProjectFiles files;
    private final BuildDecisions decisions;
    private final ProjectLeaseService leases;
    private final SandboxPort sandbox;

    public SavedBuildStore(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper,
                           ProjectFiles files, BuildDecisions decisions, ProjectLeaseService leases, SandboxPort sandbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.files = files;
        this.decisions = decisions;
        this.leases = leases;
        this.sandbox = sandbox;
        tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(10);
    }

    public record Point(UUID runId, UUID projectId, SavedBuildManifest manifest, String storage, String auth, Instant savedAt) {
        public String sourceRoot() {
            return root(runId);
        }
    }

    public static String root(UUID run) {
        return ".codepal/saved-builds/" + run + "/files";
    }

    /** Only called inside the committing run's existing mutation/lease fence, before SAVED. */
    public void capture(UUID run, UUID project) {
        var fence = RunFence.current();
        if (fence == null || !fence.runId().equals(run) || !fence.projectId().equals(project)) {
            throw new LeaseLostException();
        }
        leases.requireCurrent(project);
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_saved_builds where run_id=?)", Boolean.class, run))) {
            throw new IllegalStateException("Saved source snapshot already exists; immutable files were retained");
        }
        var state = decisions.state(project);
        if (state.blocked()) {
            throw new IllegalStateException("Cannot bind restore point to unavailable build decisions");
        }
        var manifest = SavedBuildManifest.scan(files, project, ".");
        if (manifest.files().isEmpty()) {
            return;
        }
        try {
            for (var file : manifest.files()) {
                files.copyFile(project, ".", file.path(), root(run), file.path());
            }
            if (!manifest.equals(SavedBuildManifest.scan(files, project, root(run)))
                    || !manifest.equals(SavedBuildManifest.scan(files, project, "."))) {
                throw new IllegalStateException("Saved source snapshot verification failed");
            }
        } catch (RuntimeException incomplete) {
            try {
                sandbox.removeTree(project, ".codepal/saved-builds/" + run);
            } catch (RuntimeException unavailable) {
                // No DB snapshot proof was attempted; never advertise this incomplete private directory.
            }
            throw incomplete;
        }
        var choice = state.choice();
        String storage = choice == null ? "UNAPPROVED" : choice.storage().name();
        String auth = choice == null ? "UNAPPROVED" : choice.auth().name();
        tx.executeWithoutResult(status -> {
            if (jdbc.update("insert into agent_saved_builds(run_id,project_id,manifest_json,manifest_hash,storage,auth) "
                            + "values (?,?,?,?,?,?) on conflict(run_id) do nothing", run, project,
                    mapper.writeValueAsString(manifest), manifest.digest(), storage, auth) != 1) {
                throw new IllegalStateException("Saved source snapshot already exists");
            }
        });
    }

    /** Ten newest proved saved versions; legacy before-only archives are not invented AFTER versions. */
    public List<Point> list(UUID project) {
        return jdbc.query("select s.*,p.saved_at" + SAVED + "order by p.saved_at desc,s.run_id desc limit 10",
                (row, index) -> point(row.getObject("run_id", UUID.class), project, row.getString("manifest_json"),
                        row.getString("manifest_hash"), row.getString("storage"), row.getString("auth"),
                        row.getTimestamp("saved_at").toInstant()), project);
    }

    public Point find(UUID project, UUID run) {
        return list(project).stream().filter(point -> point.runId().equals(run)).findFirst().orElse(null);
    }

    /** Called under the same fence after SAVED. Original promotion and restore recovery proofs are never removed. */
    public void retireOldBodies(UUID project) {
        leases.requireCurrent(project);
        var fence = RunFence.current();
        if (fence == null || !fence.projectId().equals(project)) {
            throw new LeaseLostException();
        }
        var obsolete = jdbc.query("select old.run_id from (select s.run_id,s.body_retired_at" + SAVED
                        + "order by p.saved_at desc,s.run_id desc offset 10) old where old.body_retired_at is null limit 20",
                (row, index) -> row.getObject("run_id", UUID.class), project);
        for (UUID run : obsolete) {
            sandbox.removeTree(project, ".codepal/saved-builds/" + run);
            jdbc.update("update agent_saved_builds set body_retired_at=clock_timestamp() where run_id=? and body_retired_at is null", run);
        }
    }

    /** Latest code event, including manual restores. An unproved legacy head deliberately returns null. */
    public SavedBuildManifest current(UUID project) {
        var point = currentPoint(project);
        return point == null ? null : point.manifest();
    }

    public UUID currentRunId(UUID project) {
        var point = currentPoint(project);
        return point == null ? null : point.runId();
    }

    private Point currentPoint(UUID project) {
        var rows = jdbc.query("select s.manifest_json,s.manifest_hash,s.storage,s.auth,e.run_id,e.event_at from ("
                        + "select id as run_id,code_saved_at as event_at from agent_runs where project_id=? and code_saved_at is not null "
                        + "union all select case when restore_files_json is not null then run_id else null end,updated_at "
                        + "from agent_code_restores where project_id=? and state='RESTORED') e "
                        + "left join agent_saved_builds s on s.run_id=e.run_id and s.project_id=? "
                        + "order by e.event_at desc,e.run_id desc nulls last limit 1",
                (row, index) -> row.getString("manifest_json") == null ? null : point(
                        row.getObject("run_id", UUID.class), project, row.getString("manifest_json"), row.getString("manifest_hash"),
                        row.getString("storage"), row.getString("auth"), row.getTimestamp("event_at").toInstant()),
                project, project, project);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Point point(UUID run, UUID project, String json, String hash, String storage, String auth, Instant at) {
        var manifest = mapper.readValue(json, SavedBuildManifest.class);
        if (!manifest.digest().equals(hash)) {
            throw new IllegalStateException("Saved source manifest proof changed");
        }
        return new Point(run, project, manifest, storage, auth, at);
    }
}
