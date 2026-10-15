package com.code.pal.agent.validation;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.brief.ApprovedProjectMemory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.exception.WorkflowStageException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Internal coordinator-owned criteria; agent tools cannot write the store. Requirements remain authoritative.
 * A follow-up run without its own criteria inherits the last completed run's browser checks as regression
 * checks; the agent may only retire inherited checks (with a reason), never add or rewrite them.
 */
@Service
public final class RunBehaviorCriteria {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovedProjectMemory memory;
    private BuildDecisions decisions;

    @Autowired
    public void useDecisions(BuildDecisions decisions) {
        this.decisions = Objects.requireNonNull(decisions);
    }

    public RunBehaviorCriteria(JdbcTemplate jdbc, ObjectMapper mapper, ApprovedProjectMemory memory) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.memory = memory;
    }

    public BehaviorCriteria current(AgentContext ctx) {
        var rows = jdbc.query(
                "select requirements_projection_id,requirements_revision,criteria_json from agent_behavior_criteria where run_id=? and project_id=?",
                (rs, row) -> new Object[] { rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3) }, ctx.runId(),
                ctx.projectId());
        if (rows.isEmpty())
            return null;
        var approved = memory.current(ctx.projectId());
        var row = rows.getFirst();
        if (approved == null || !approved.projectionId().equals(row[0])
                || approved.revision() != ((Long) row[1]).longValue())
            throw new WorkflowStageException(
                    "Required acceptance is stale; approve current requirements and start a new run");
        var criteria = mapper.readValue((String) row[2], BehaviorCriteria.class);
        checkCapabilities(ctx.projectId(), criteria);
        return criteria;
    }

    /** Browser checks of the last completed run with acceptance, minus retired ones; null when none apply. */
    public BehaviorCriteria regression(AgentContext ctx) {
        var approved = memory.current(ctx.projectId());
        if (approved == null)
            return null;
        var rows = jdbc.query("select c.criteria_json,c.created_at,c.requirements_projection_id,c.requirements_revision "
                + "from agent_behavior_criteria c join agent_runs r on r.id=c.run_id "
                + "where c.project_id=? and c.run_id<>? and r.status='DONE' order by c.created_at desc limit 1",
                (rs, row) -> new Source(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, UUID.class),
                        rs.getLong(4)), ctx.projectId(), ctx.runId());
        if (rows.isEmpty() || !approved.projectionId().equals(rows.getFirst().projection())
                || approved.revision() != rows.getFirst().revision())
            return null;
        var source = rows.getFirst();
        var retired = new HashSet<>(jdbc.queryForList("select t.action_id from agent_acceptance_retirements t "
                + "join agent_runs r on r.id=t.run_id where t.project_id=? and t.created_at>? and (t.run_id=? or r.status='DONE')",
                String.class, ctx.projectId(), source.created(), ctx.runId()));
        var browser = mapper.readValue(source.json(), BehaviorCriteria.class).browser().stream()
                .filter(action -> !retired.contains(action.id())).toList();
        return browser.isEmpty() ? null : new BehaviorCriteria(1, browser, List.of(), false, false);
    }

    /** Retires inherited checks the user's request intentionally changes or removes; returns what remains. */
    public List<String> retire(AgentContext ctx, List<String> ids, String reason) {
        if (current(ctx) != null)
            throw new IllegalArgumentException("This run's own approved acceptance cannot be retired");
        var inherited = regression(ctx);
        if (inherited == null)
            throw new IllegalArgumentException("No earlier acceptance checks apply to this change");
        var known = inherited.browser().stream().map(BehaviorCriteria.BrowserAction::id).toList();
        if (ids == null || ids.isEmpty() || ids.size() > 24 || !known.containsAll(ids))
            throw new IllegalArgumentException("Unknown acceptance check id; current ids: " + known);
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > 300)
            throw new IllegalArgumentException("Give a short reason (at most 300 characters)");
        // One statement: every id or none, and only for this project's running, unstopped BUILD run of this user.
        int inserted = jdbc.update("insert into agent_acceptance_retirements(run_id,project_id,action_id,reason) "
                + "select r.id,r.project_id,unnest(?::varchar[]),? from agent_runs r where r.id=? and r.project_id=? "
                + "and r.user_id=? and r.mode='BUILD' and r.status='RUNNING' and r.cancel_requested_at is null "
                + "on conflict do nothing", ids.toArray(String[]::new), why, ctx.runId(), ctx.projectId(), ctx.userId());
        if (inserted == 0 && jdbc.queryForObject("select count(*) from agent_acceptance_retirements where run_id=? and "
                + "action_id = any(?::varchar[])", Integer.class, ctx.runId(), ids.toArray(String[]::new)) < ids.size())
            throw new IllegalStateException("Only the running build of this change can retire its inherited checks");
        return known.stream().filter(id -> !ids.contains(id)).toList();
    }

    private record Source(String json, OffsetDateTime created, UUID projection, long revision) {
    }

    public void install(UUID run, UUID project, UUID projection, long revision, BehaviorCriteria criteria) {
        if (jdbc.queryForObject("select count(*) from agent_runs where id=? and project_id=?", Integer.class, run,
                project) != 1)
            throw new IllegalStateException("Acceptance run/project mismatch");
        var current = memory.current(project);
        if (current == null || !current.projectionId().equals(projection) || current.revision() != revision)
            throw new IllegalStateException("Acceptance requirements are stale");
        checkCapabilities(project, criteria);
        String json = mapper.writeValueAsString(criteria);
        jdbc.update(
                "insert into agent_behavior_criteria(run_id,project_id,requirements_projection_id,requirements_revision,criteria_json) values (?,?,?,?,?) on conflict(run_id) do nothing",
                run, project, projection, revision, json);
        String installed = jdbc.queryForObject(
                "select criteria_json from agent_behavior_criteria where run_id=? and project_id=?", String.class, run,
                project);
        if (!json.equals(installed))
            throw new IllegalStateException("Run acceptance criteria are immutable");
    }

    private void checkCapabilities(UUID project, BehaviorCriteria criteria) {
        if (decisions == null)
            return;
        var state = decisions.state(project);
        if (state.blocked() || state.choice() == null)
            throw new IllegalStateException("Acceptance build decisions are unavailable");
        if (state.choice().auth() == BuildDecisions.Auth.ACCOUNTS && !criteria.authRequired())
            throw new IllegalArgumentException("Approved accounts require authentication acceptance");
    }
}
