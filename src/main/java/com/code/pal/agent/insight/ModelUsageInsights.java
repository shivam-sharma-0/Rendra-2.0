package com.code.pal.agent.insight;

import com.code.pal.project.service.ProjectService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Project-scoped physical-attempt evidence, including failed and unpriced work. */
@Service
public final class ModelUsageInsights {
    public record Stage(String purpose, long attempts, long reportedAttempts, long pricedAttempts,
            Long promptTokens, Long completionTokens, Long cachedInputTokens, Long reasoningOutputTokens,
            BigDecimal pricedSubtotalUsd, Long durationMs) {}
    public record Summary(List<Stage> stages, boolean usageComplete, boolean costComplete) {}
    private final ProjectService projects;
    private final JdbcTemplate jdbc;

    public ModelUsageInsights(ProjectService projects, JdbcTemplate jdbc) {
        this.projects = projects;
        this.jdbc = jdbc;
    }

    public Summary forProject(Long user, UUID project) {
        projects.requireAccessible(user, project);
        List<Stage> stages = jdbc.query("select a.purpose,count(*) as attempts, "
                        + "count(*) filter(where a.state='REPORTED') as reported, count(c.estimated_cost_usd) as priced, "
                        + "case when count(*) filter(where a.state='REPORTED')=count(*) then sum(a.prompt_tokens) end as inputs, "
                        + "case when count(*) filter(where a.state='REPORTED')=count(*) then sum(a.completion_tokens) end as outputs, "
                        + "case when count(c.cached_input_tokens)=count(*) then sum(c.cached_input_tokens) end as cached, "
                        + "case when count(c.reasoning_output_tokens)=count(*) then sum(c.reasoning_output_tokens) end as reasoning, "
                        + "sum(c.estimated_cost_usd) as cost,case when count(c.duration_ms)=count(*) then sum(c.duration_ms) end as duration "
                        + "from ai_token_attempts a left join agent_model_calls c on c.attempt_id=a.id "
                        + "where a.project_id=? and a.sent_at is not null group by a.purpose order by a.purpose",
                (row, index) -> new Stage(row.getString("purpose"), row.getLong("attempts"), row.getLong("reported"),
                        row.getLong("priced"), nullableLong(row, "inputs"), nullableLong(row, "outputs"),
                        nullableLong(row, "cached"), nullableLong(row, "reasoning"),
                        row.getBigDecimal("cost"), nullableLong(row, "duration")), project);
        boolean known = !stages.isEmpty() && stages.stream().allMatch(stage -> stage.attempts() == stage.reportedAttempts());
        return new Summary(stages, known, known && stages.stream().allMatch(stage -> stage.attempts() == stage.pricedAttempts()));
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }
}
