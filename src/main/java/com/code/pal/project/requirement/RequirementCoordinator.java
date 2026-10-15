package com.code.pal.project.requirement;

import com.code.pal.project.exception.WorkflowStageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Short transaction coordination only. An expired uncertain call uses fallback, never a second send. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class RequirementCoordinator {
    public record Claim(UUID owner, boolean fallback) {}
    private final JdbcTemplate jdbc;
    public RequirementCoordinator(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void lock(UUID project) {
        jdbc.execute("set local lock_timeout='5s'");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,92831))", Object.class, project.toString());
    }

    public Claim claim(UUID session, long revision, String purpose, Long actor) {
        UUID owner = UUID.randomUUID();
        int inserted = jdbc.update("insert into requirement_llm_work(session_id,revision,purpose,owner_id,user_id,state,expires_at) "
                + "values (?,?,?,?,?,'RUNNING',clock_timestamp()+interval '2 minutes') on conflict do nothing", session, revision, purpose, owner, actor);
        if (inserted == 1) return new Claim(owner, false);
        int fallback = jdbc.update("update requirement_llm_work set owner_id=?,user_id=?,state='FALLBACK',expires_at=clock_timestamp()+interval '2 minutes' "
                + "where session_id=? and revision=? and purpose=? and state in ('RUNNING','FALLBACK') and expires_at<=clock_timestamp()", owner, actor, session, revision, purpose);
        return fallback == 1 ? new Claim(owner, true) : null;
    }

    public boolean finish(UUID session, long revision, String purpose, Claim claim, String outcome) {
        return jdbc.update("update requirement_llm_work set state='DONE',outcome=? "
                + "where session_id=? and revision=? and purpose=? and owner_id=? and state in ('RUNNING','FALLBACK')",
                outcome, session, revision, purpose, claim.owner()) == 1;
    }

    public boolean processing(UUID session, long revision) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from requirement_llm_work where session_id=? and revision=? and state <> 'DONE')",
                Boolean.class, session, revision));
    }

    public void requireNoActiveBuild(UUID project) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from agent_runs where project_id=? and mode='BUILD' and status in ('QUEUED','RUNNING','COMMITTING'))", Boolean.class, project))) {
            throw new WorkflowStageException("Finish or stop the current build before editing approved requirements");
        }
    }

    public String projectionState(UUID projection) {
        if (projection == null) return "NONE";
        return jdbc.queryForObject("select state from requirement_projections where id=?", String.class, projection);
    }
    public String reviewState(UUID session, long revision) {
        var rows = jdbc.query("select case when state <> 'DONE' then 'PROCESSING' when outcome='FALLBACK' then 'FALLBACK' when outcome='COMPLETE' then 'MODEL' else 'NONE' end from requirement_llm_work where session_id=? and revision=? and purpose='ASSESSMENT'",
                (r, n) -> r.getString(1), session, revision);
        return rows.isEmpty() ? "NONE" : rows.getFirst();
    }
}
