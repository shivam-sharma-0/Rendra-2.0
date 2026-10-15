package com.code.pal.agent.validation;

import com.code.pal.agent.tool.AgentContext;
import com.code.pal.project.brief.ApprovedProjectMemory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.UUID;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.project.exception.WorkflowStageException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;

/** Internal coordinator-owned criteria; agent tools cannot write the store. Requirements remain authoritative. */
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
