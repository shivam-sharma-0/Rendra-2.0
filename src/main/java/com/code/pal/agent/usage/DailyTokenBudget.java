package com.code.pal.agent.usage;

import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.util.Objects;
import java.util.UUID;
import com.code.pal.agent.llm.LlmQuotaExceededException;
import java.util.List;

/** A DB transaction owns both reservation and settlement. Missing usage never expires or refunds itself. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
public class DailyTokenBudget {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private ModelCallPricing pricing;

    @Autowired(required = false)
    void usePricing(ModelCallPricing pricing) {
        this.pricing = pricing;
    }

    @Autowired
    public DailyTokenBudget(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DailyTokenBudget(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Request(UUID id, UUID logicalCallId, LlmCallContext context, String provider, String model,
            long inputBound, int maxOutputTokens, long quota, long inputLimit) {
        public Request(UUID id, UUID logicalCallId, LlmCallContext context, String provider, String model,
                long inputBound, int maxOutputTokens, long quota) {
            this(id, logicalCallId, context, provider, model, inputBound, maxOutputTokens, quota, inputBound);
        }
    }

    public record Grant(UUID id, Long userId, LocalDate period, long tokens, boolean granted, String refusal) {
    }

    public record Usage(long charged, long reserved, long known, long unknown, long unknownCalls) {
        public long committedAndReserved() {
            return Math.addExact(charged, reserved);
        }
    }

    private record Attempt(UUID id, UUID logicalId, Long userId, LocalDate period, long tokens,
            String state, Long prompt, Long completion, UUID runId, long quota, long inputBound, int outputCap,
            long inputLimit, long unknownCharge) {
    }

    public Grant reserve(Request request) {
        Objects.requireNonNull(request.id());
        Objects.requireNonNull(request.logicalCallId());
        Objects.requireNonNull(request.context());
        Long user = Objects.requireNonNull(request.context().userId(), "A model call needs an accounting owner");
        if (request.inputBound() < 0 || request.inputLimit() < request.inputBound() || request.maxOutputTokens() <= 0
                || request.quota() < 0
                || request.provider() == null || request.model() == null)
            throw new IllegalArgumentException("Invalid bounded model request");
        long amount = Math.addExact(request.inputBound(), request.maxOutputTokens());
        // Serialize even the first insertion of an attempt id. Always lock attempt before period.
        jdbc.query("select pg_advisory_xact_lock(?)", rs -> {
        }, request.id().getMostSignificantBits() ^ request.id().getLeastSignificantBits());
        Attempt existing = attempt(request.id());
        if (existing != null) {
            if (!existing.userId().equals(user) || !existing.logicalId().equals(request.logicalCallId())
                    || existing.tokens() != amount || !identityMatches(request))
                throw new IllegalStateException("Attempt identity was reused for a different request");
            return new Grant(existing.id(), user, existing.period(), amount, true, null);
        }
        if (jdbc.queryForObject("select count(*) from ai_accounting_blocks where provider=? and model=?", Integer.class,
                request.provider(), request.model()) > 0)
            throw new IllegalStateException("Provider/model accounting is suspended");
        if (request.context().runId() != null) {
            ensureRun(request.context().runId(), user, 500_000);
            var run = lockRun(request.context().runId());
            if (run.user() != user.longValue())
                throw new IllegalStateException("Run accounting owner mismatch");
            if (run.charged() + run.reserved() > run.quota() - amount)
                return new Grant(request.id(), user, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), amount,
                        false, "This run's token budget is exhausted");
            jdbc.update("update ai_run_budgets set reserved_tokens=reserved_tokens+? where run_id=?", amount,
                    request.context().runId());
        }
        LocalDate period = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Usage current = lockPeriod(user, period, request.quota());
        if (request.quota() > 0 && current.committedAndReserved() > request.quota() - amount) {
            if (request.context().runId() != null)
                jdbc.update("update ai_run_budgets set reserved_tokens=reserved_tokens-? where run_id=?", amount,
                        request.context().runId());
            return new Grant(request.id(), user, period, amount, false, AgentUsagePolicy.QUOTA_LOW_MESSAGE);
        }
        jdbc.update("update ai_budget_periods set reserved_tokens=reserved_tokens+? where user_id=? and period_start=?",
                amount, user, Date.valueOf(period));
        var context = request.context();
        jdbc.update(
                "insert into ai_token_attempts(id,logical_call_id,user_id,project_id,run_id,purpose,provider,model,period_start,input_bound,max_output_tokens,reserved_tokens,state,created_at,counted_in_run,daily_quota,input_limit) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED',?,?,?,?)",
                request.id(), request.logicalCallId(), user, context.projectId(), context.runId(),
                context.purpose().name(),
                request.provider(), request.model(), Date.valueOf(period), request.inputBound(),
                request.maxOutputTokens(), amount, Timestamp.from(clock.instant()), context.countedInRun(),
                request.quota(), request.inputLimit());
        return new Grant(request.id(), user, period, amount, true, null);
    }

    /** Only the first owner may transmit this attempt. A released or previously sent id cannot send again. */
    public void markSent(UUID id) {
        Attempt attempt = required(id);
        if (!"RESERVED".equals(attempt.state()))
            throw new IllegalStateException("Attempt is not available to send");
        Instant sendingAt = clock.instant();
        LocalDate now = LocalDate.ofInstant(sendingAt, ZoneOffset.UTC);
        if (!attempt.period().equals(now)) {
            if (attempt.runId() != null)
                lockRun(attempt.runId());
            // Always lock days in chronological order, including a clock correction.
            if (now.isAfter(attempt.period()))
                lockExistingPeriod(attempt.userId(), attempt.period());
            Usage destination = lockPeriod(attempt.userId(), now, attempt.quota());
            if (now.isBefore(attempt.period()))
                lockExistingPeriod(attempt.userId(), attempt.period());
            if (attempt.quota() > 0 && destination.committedAndReserved() > attempt.quota() - attempt.tokens())
                throw new LlmQuotaExceededException(AgentUsagePolicy.QUOTA_LOW_MESSAGE);
            jdbc.update(
                    "update ai_budget_periods set reserved_tokens=reserved_tokens-? where user_id=? and period_start=?",
                    attempt.tokens(), attempt.userId(), Date.valueOf(attempt.period()));
            jdbc.update(
                    "update ai_budget_periods set reserved_tokens=reserved_tokens+? where user_id=? and period_start=?",
                    attempt.tokens(), attempt.userId(), Date.valueOf(now));
            jdbc.update("update ai_token_attempts set period_start=? where id=?", Date.valueOf(now), id);
        }
        jdbc.update("update ai_token_attempts set state='SENT',sent_at=? where id=?", Timestamp.from(sendingAt), id);
    }

    public void settleReported(UUID id, LlmClient.Usage report) {
        if (report == null || report.promptTokens() < 0 || report.completionTokens() < 0)
            throw new IllegalArgumentException("Reported usage must be present and nonnegative");
        Attempt attempt = required(id);
        if ("REPORTED".equals(attempt.state())) {
            if (!Objects.equals(attempt.prompt(), (long) report.promptTokens())
                    || !Objects.equals(attempt.completion(), (long) report.completionTokens()))
                throw new IllegalStateException("Conflicting usage for an already settled attempt");
            return;
        }
        if (!"SENT".equals(attempt.state()) && !"UNKNOWN".equals(attempt.state()))
            throw new IllegalStateException("Only a possibly sent attempt can have billed usage");
        long actual = (long) report.promptTokens() + report.completionTokens();
        boolean unknown = "UNKNOWN".equals(attempt.state());
        if (attempt.runId() != null)
            lockRun(attempt.runId());
        lockExistingPeriod(attempt.userId(), attempt.period());
        jdbc.update("update ai_budget_periods set reserved_tokens=reserved_tokens-?,charged_tokens=charged_tokens+?,"
                + "known_tokens=known_tokens+?,unknown_tokens=unknown_tokens-?,unknown_calls=unknown_calls-? where user_id=? and period_start=?",
                unknown ? 0 : attempt.tokens(), actual - (unknown ? attempt.unknownCharge() : 0), actual,
                unknown ? attempt.unknownCharge() : 0, unknown ? 1 : 0, attempt.userId(),
                Date.valueOf(attempt.period()));
        adjustRun(attempt, unknown ? 0 : -attempt.tokens(), actual - (unknown ? attempt.unknownCharge() : 0));
        boolean exceeded = report.promptTokens() > attempt.inputLimit()
                || report.completionTokens() > attempt.outputCap();
        if (exceeded) {
            jdbc.update("update ai_token_attempts set bound_exceeded=true where id=?", id);
            jdbc.update(
                    "insert into ai_accounting_blocks(provider,model,reason,created_at) select provider,model,'Usage exceeded verified ceiling',? from ai_token_attempts where id=? on conflict do nothing",
                    Timestamp.from(clock.instant()), id);
        }
        jdbc.update(
                "update ai_token_attempts set state='REPORTED',prompt_tokens=?,completion_tokens=?,settled_at=? where id=?",
                report.promptTokens(), report.completionTokens(), Timestamp.from(clock.instant()), id);
        jdbc.update(
                "update agent_model_calls set prompt_tokens=?,completion_tokens=?,usage_known=true where attempt_id=?",
                report.promptTokens(), report.completionTokens(), id);
    }

    /** Budget, attempt and ledger close in one transaction, even inside a rollbackable product transaction. */
    public void finishAttempt(UUID id, LlmClient.Usage report, String outcome, String error, int elapsedMs) {
        required(id);
        boolean known = report != null && report.promptTokens() >= 0 && report.completionTokens() >= 0;
        if (known)
            settleReported(id, report);
        else if ("CANCELLED".equals(outcome))
            retainUnknownCharge(id, required(id).inputBound());
        else
            retainUnknown(id);
        String safeError = error == null ? null : error.substring(0, Math.min(300, error.length()));
        jdbc.update(
                "insert into agent_model_calls(id,attempt_id,logical_call_id,user_id,project_id,run_id,purpose,provider,model,"
                        + "prompt_tokens,completion_tokens,estimated_input_tokens,max_tokens,duration_ms,outcome,error,counted_in_run,created_at,usage_known) "
                        + "select id,id,logical_call_id,user_id,project_id,run_id,purpose,provider,model,"
                        + "case when state='REPORTED' then prompt_tokens else 0 end,case when state='REPORTED' then completion_tokens else 0 end,"
                        + "least(input_bound,2147483647),max_output_tokens,?,?,?,counted_in_run,coalesce(sent_at,created_at),state='REPORTED' "
                        + "from ai_token_attempts where id=? "
                        + "on conflict (attempt_id) where attempt_id is not null do update set "
                        + "prompt_tokens=excluded.prompt_tokens,completion_tokens=excluded.completion_tokens,usage_known=excluded.usage_known,"
                        + "duration_ms=excluded.duration_ms,outcome=excluded.outcome,error=excluded.error "
                        + "where agent_model_calls.usage_known is distinct from true or agent_model_calls.outcome='UNKNOWN'",
                Math.max(0, elapsedMs), outcome, safeError, id);
        if (known) recordUsageDetails(id, report);
    }

    private void recordUsageDetails(UUID id, LlmClient.Usage usage) {
        boolean priced = pricing != null && pricing.configured();
        if (!priced && usage.cachedInputTokens() == null && usage.reasoningOutputTokens() == null) return;
        ModelCallPricing.Estimate estimate = null;
        if (priced) {
            var rows = jdbc.query("select provider,model from agent_model_calls where attempt_id=?",
                    (result, row) -> new String[] {result.getString(1), result.getString(2)}, id);
            if (!rows.isEmpty()) estimate = pricing.estimate(rows.getFirst()[0], rows.getFirst()[1], usage);
        }
        jdbc.update("update agent_model_calls set cached_input_tokens=coalesce(cached_input_tokens,?), "
                        + "reasoning_output_tokens=coalesce(reasoning_output_tokens,?), "
                        + "estimated_cost_usd=coalesce(estimated_cost_usd,?), cost_estimate_kind=coalesce(cost_estimate_kind,?), "
                        + "price_revision=coalesce(price_revision,?) where attempt_id=?",
                usage.cachedInputTokens(), usage.reasoningOutputTokens(), estimate == null ? null : estimate.amountUsd(),
                estimate == null ? null : estimate.kind(), estimate == null ? null : estimate.revision(), id);
    }

    /** Accounting remains conservative after cancellation, worker death, missing usage or settlement failure. */
    public void retainUnknown(UUID id) {
        retainUnknownCharge(id, required(id).tokens());
    }

    private void retainUnknownCharge(UUID id, long charge) {
        Attempt attempt = required(id);
        if ("UNKNOWN".equals(attempt.state()) || "REPORTED".equals(attempt.state()))
            return;
        if (!"SENT".equals(attempt.state()))
            throw new IllegalStateException("Unsent attempt cannot be billed as unknown");
        if (attempt.runId() != null)
            lockRun(attempt.runId());
        lockExistingPeriod(attempt.userId(), attempt.period());
        jdbc.update("update ai_budget_periods set reserved_tokens=reserved_tokens-?,charged_tokens=charged_tokens+?,"
                + "unknown_tokens=unknown_tokens+?,unknown_calls=unknown_calls+1 where user_id=? and period_start=?",
                attempt.tokens(), charge, charge, attempt.userId(), Date.valueOf(attempt.period()));
        adjustRun(attempt, -attempt.tokens(), charge);
        jdbc.update("update ai_token_attempts set state='UNKNOWN',unknown_charge_tokens=?,settled_at=? where id=?",
                charge, Timestamp.from(clock.instant()), id);
    }

    /** Safe only because markSent checks durable state before any send, including a resumed worker. */
    public void releaseUnsent(UUID id) {
        Attempt attempt = required(id);
        if ("NOT_SENT".equals(attempt.state()))
            return;
        if (!"RESERVED".equals(attempt.state()))
            throw new IllegalStateException("A possibly sent attempt cannot be refunded");
        if (attempt.runId() != null)
            lockRun(attempt.runId());
        lockExistingPeriod(attempt.userId(), attempt.period());
        jdbc.update("update ai_budget_periods set reserved_tokens=reserved_tokens-? where user_id=? and period_start=?",
                attempt.tokens(), attempt.userId(), Date.valueOf(attempt.period()));
        adjustRun(attempt, -attempt.tokens(), 0);
        jdbc.update("update ai_token_attempts set state='NOT_SENT',settled_at=? where id=?",
                Timestamp.from(clock.instant()), id);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Usage usage(Long user, LocalDate period) {
        var rows = jdbc.query(
                "select charged_tokens,reserved_tokens,known_tokens,unknown_tokens,unknown_calls from ai_budget_periods where user_id=? and period_start=?",
                (rs, row) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), user,
                Date.valueOf(period));
        return rows.isEmpty() ? new Usage(0, 0, 0, 0, 0) : rows.getFirst();
    }

    public boolean boundExceeded(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select bound_exceeded from ai_token_attempts where id=?", Boolean.class, id));
    }

    public record ProjectUsage(long reportedTokens, long unknownTokens, long reservedTokens, long unknownCalls,
            long actualAttempts, long logicalCalls, long historicalAttemptRecords) {
        public long chargedTokens() {
            return Math.addExact(reportedTokens, unknownTokens);
        }
    }

    /** One snapshot across attempts and one-time legacy reconciliation; run display counters are not added again. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public ProjectUsage projectUsage(UUID project) {
        return jdbc.queryForObject(
                """
                        with current_usage as (
                          select coalesce(sum(case when state='REPORTED' then prompt_tokens+completion_tokens else 0 end),0) known,
                                 coalesce(sum(case when state='UNKNOWN' then unknown_charge_tokens else 0 end),0) unknown,
                                 coalesce(sum(case when state in ('RESERVED','SENT') then reserved_tokens else 0 end),0) reserved,
                                 count(*) filter(where state='UNKNOWN') unknown_calls,
                                 count(*) filter(where state in ('SENT','REPORTED','UNKNOWN')) attempts,
                                 count(distinct logical_call_id) filter(where state in ('SENT','REPORTED','UNKNOWN')) logical
                          from ai_token_attempts where project_id=?
                        ), legacy as (
                          select coalesce(sum(known_tokens),0) known,coalesce(sum(unknown_tokens),0) unknown,
                                 coalesce(sum(unknown_calls),0) unknown_calls,count(*) filter(where source_key like 'call:%') records
                          from ai_legacy_usage where project_id=?
                        ) select c.known+l.known,c.unknown+l.unknown,c.reserved,c.unknown_calls+l.unknown_calls,c.attempts,c.logical,l.records
                          from current_usage c cross join legacy l
                        """,
                (rs, row) -> new ProjectUsage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                        rs.getLong(6), rs.getLong(7)),
                project, project);
    }

    /** Set a deterministic tier cap once. Later planner or retry calls cannot raise it. */
    public void prepareRun(UUID id, Long user, long quota) {
        if (quota <= 0)
            throw new IllegalArgumentException("Run budget must be positive");
        ensureRun(id, user, quota);
    }

    public void configureRun(UUID id, Long user, long quota) {
        if (quota <= 0)
            throw new IllegalArgumentException("Run budget must be positive");
        ensureRun(id, user, quota);
        var run = lockRun(id);
        if (run.user() != user.longValue())
            throw new IllegalStateException("Run accounting owner mismatch");
        boolean configured = Boolean.TRUE
                .equals(jdbc.queryForObject("select configured from ai_run_budgets where run_id=?", Boolean.class, id));
        if (configured && quota != run.quota())
            throw new IllegalStateException("Run budget is already fixed");
        jdbc.update("update ai_run_budgets set quota_tokens=?,configured=true where run_id=?", quota, id);
    }

    /** bounded replay: released unsent IDs can never transmit; possibly sent calls keep their full bound. */
    public List<UUID> pendingRecovery(Instant before, int limit) {
        return jdbc.query(
                "select id from ai_token_attempts a where ((state='RESERVED' and created_at<?) or (state='SENT' and sent_at<?) or (state in ('UNKNOWN','REPORTED') and not exists(select 1 from agent_model_calls c where c.attempt_id=a.id))) order by created_at limit ?",
                (rs, row) -> rs.getObject(1, UUID.class), Timestamp.from(before), Timestamp.from(before),
                Math.max(1, Math.min(100, limit)));
    }

    public void recover(UUID id, Instant before) {
        Attempt attempt = required(id);
        Instant created = jdbc.queryForObject("select coalesce(sent_at,created_at) from ai_token_attempts where id=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), id);
        if ("RESERVED".equals(attempt.state())) {
            if (created.isBefore(before))
                releaseUnsent(id);
            return;
        }
        if ("SENT".equals(attempt.state()) && !created.isBefore(before))
            return;
        if ("NOT_SENT".equals(attempt.state()))
            return;
        // Canonical reported usage survives a delayed ledger replay; unknown is never replaced by zero.
        LlmClient.Usage report = "REPORTED".equals(attempt.state())
                ? new LlmClient.Usage(Math.toIntExact(attempt.prompt()), Math.toIntExact(attempt.completion()))
                : null;
        finishAttempt(id, report, "UNKNOWN", null, 0);
    }

    private record RunBudget(long user, long quota, long charged, long reserved) {
    }

    private void ensureRun(UUID run, Long user, long quota) {
        jdbc.update("insert into ai_run_budgets(run_id,user_id,quota_tokens) values (?,?,?) on conflict do nothing",
                run, user, quota);
    }

    private RunBudget lockRun(UUID id) {
        return jdbc.queryForObject(
                "select user_id,quota_tokens,charged_tokens,reserved_tokens from ai_run_budgets where run_id=? for update",
                (rs, row) -> new RunBudget(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), id);
    }

    private void adjustRun(Attempt attempt, long reserved, long charged) {
        if (attempt.runId() != null)
            jdbc.update(
                    "update ai_run_budgets set reserved_tokens=reserved_tokens+?,charged_tokens=charged_tokens+? where run_id=?",
                    reserved, charged, attempt.runId());
    }

    private Usage lockPeriod(Long user, LocalDate period, long quota) {
        jdbc.update(
                "insert into ai_budget_periods(user_id,period_start,quota_tokens) values (?,?,?) on conflict (user_id,period_start) do nothing",
                user, Date.valueOf(period), quota);
        Usage current = lockExistingPeriod(user, period);
        jdbc.update("update ai_budget_periods set quota_tokens=? where user_id=? and period_start=?", quota, user,
                Date.valueOf(period));
        return current;
    }

    private Usage lockExistingPeriod(Long user, LocalDate period) {
        return jdbc.queryForObject(
                "select charged_tokens,reserved_tokens,known_tokens,unknown_tokens,unknown_calls from ai_budget_periods where user_id=? and period_start=? for update",
                (rs, row) -> new Usage(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)), user,
                Date.valueOf(period));
    }

    private Attempt required(UUID id) {
        Attempt attempt = attempt(id);
        if (attempt == null)
            throw new IllegalStateException("Attempt has no durable accounting ownership");
        return attempt;
    }

    private boolean identityMatches(Request request) {
        return jdbc.queryForObject(
                "select count(*) from ai_token_attempts where id=? and project_id is not distinct from ? "
                        + "and run_id is not distinct from ? and purpose=? and provider=? and model=? and input_bound=? and max_output_tokens=? and counted_in_run=? and input_limit=?",
                Integer.class, request.id(), request.context().projectId(), request.context().runId(),
                request.context().purpose().name(),
                request.provider(), request.model(), request.inputBound(), request.maxOutputTokens(),
                request.context().countedInRun(), request.inputLimit()) == 1;
    }

    private Attempt attempt(UUID id) {
        var rows = jdbc.query(
                "select id,logical_call_id,user_id,period_start,reserved_tokens,state,prompt_tokens,completion_tokens,run_id,daily_quota,input_bound,max_output_tokens,input_limit,coalesce(unknown_charge_tokens,0) from ai_token_attempts where id=? for update",
                (rs, row) -> new Attempt(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                        rs.getDate(4).toLocalDate(), rs.getLong(5), rs.getString(6), rs.getObject(7, Long.class),
                        rs.getObject(8, Long.class), rs.getObject(9, UUID.class), rs.getLong(10), rs.getLong(11),
                        rs.getInt(12), rs.getLong(13), rs.getLong(14)),
                id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
