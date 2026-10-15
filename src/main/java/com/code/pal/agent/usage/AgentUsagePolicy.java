package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.llm.LlmCallContext;
import com.code.pal.agent.llm.LlmClient;
import com.code.pal.agent.llm.LlmAccountingException;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.shared.usage.UsageLimitExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** User admission and durable daily capacity. Redis supplies only the optional per-minute counter. */
@Slf4j
@Component
public class AgentUsagePolicy implements AgentTurnAdmission {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING,RunStatus.COMMITTING);
    private static final String RATE_KEY_PREFIX = "agent:turns:";

    private final AgentProperties properties;
    private final AgentRunRepository runs;
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final DailyTokenBudget budget;

    @Autowired
    public AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, DailyTokenBudget budget) {
        this(properties, runs, redis, Clock.systemUTC(), budget);
    }

    AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, Clock clock,
                     DailyTokenBudget budget) {
        this.properties = properties;
        this.runs = runs;
        this.redis = redis;
        this.clock = clock;
        this.budget = budget;
    }

    @Override
    public void requireAdmitted(Long userId) {
        AgentProperties.Limits limits = properties.getLimits();
        long quota = limits.getDailyTokenQuota();
        if (quota > 0 && usageToday(userId).committedAndReserved() >= quota) {
            log.info("usage_limit userId={} limit=daily_tokens quota={}", userId, quota);
            throw new UsageLimitExceededException(QUOTA_MESSAGE);
        }
        int maxActive = limits.getMaxActiveRunsPerUser();
        if (maxActive > 0 && runs.countByUserIdAndStatusIn(userId, ACTIVE) >= maxActive) {
            log.info("usage_limit userId={} limit=active_runs max={}", userId, maxActive);
            throw new UsageLimitExceededException(maxActive == 1
                    ? "You already have a request in progress. Wait for it to finish, or stop it, before sending another."
                    : "You already have " + maxActive + " requests in progress. Wait for one to finish, or stop it,"
                            + " before sending another.");
        }
        int perMinute = limits.getTurnsPerMinute();
        if (perMinute > 0 && turnsThisMinute(userId) > perMinute) {
            log.info("usage_limit userId={} limit=turns_per_minute max={}", userId, perMinute);
            throw new UsageLimitExceededException("Too many requests. Please wait a minute and try again.");
        }
    }

    public static final String QUOTA_MESSAGE = "You have used today's AI quota. It resets at 00:00 UTC.";
    /** When the quota is not used up but cannot cover the next model call's worst case. */
    public static final String QUOTA_LOW_MESSAGE = "Not enough of today's AI quota is left for this request. It resets at 00:00 UTC.";

    public DailyTokenBudget.Grant reserve(LlmCallContext context, LlmClient.LlmRequest request, long input,
                                         java.util.UUID logicalId, java.util.UUID attemptId) {
        try {
            if(context.runId()!=null) budget.prepareRun(context.runId(),context.userId(),properties.getSmallRunTokenBudget());
            return budget.reserve(new DailyTokenBudget.Request(attemptId, logicalId, context,
                    request.provider(), request.model(), input, request.maxTokens(), dailyTokenQuota(),VerifiedTokenBounds.input(properties,request)));
        } catch (RuntimeException unavailable) { throw new LlmAccountingException(unavailable); }
    }

    public void markSent(java.util.UUID id) { budget.markSent(id); }
    public void settle(java.util.UUID id, LlmClient.Usage report) { budget.settleReported(id, report); }
    public void retainUnknown(java.util.UUID id) { budget.retainUnknown(id); }
    public void releaseUnsent(java.util.UUID id) { budget.releaseUnsent(id); }
    public void finishAttempt(java.util.UUID id, LlmClient.Usage report, String outcome, String error, int elapsedMs) {
        budget.finishAttempt(id, report, outcome, error, elapsedMs);
    }
    public boolean boundExceeded(java.util.UUID id) {
        try { return budget.boundExceeded(id); } catch (RuntimeException unavailable) { throw new LlmAccountingException(unavailable); }
    }
    public void configureRun(java.util.UUID id,Long user,com.code.pal.agent.route.SizeTier tier) {
        long cap=switch(tier) {
            case S -> properties.getSmallRunTokenBudget();
            case M -> properties.getMediumRunTokenBudget();
            case L -> properties.getLargeRunTokenBudget();
        };
        budget.configureRun(id,user,cap);
    }

    /** Durable reported/unknown charge for today's UTC period; reservations are tracked separately. */
    public long tokensUsedToday(Long userId) {
        return usageToday(userId).charged();
    }

    private DailyTokenBudget.Usage usageToday(Long userId) {
        return budget.usage(userId, java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC));
    }

    /** Today's token use against the daily quota, and when the count resets (next 00:00 UTC). */
    public DailyUsage dailyUsage(Long userId) {
        Instant startOfDay = startOfToday();
        var usage=usageToday(userId);
        return new DailyUsage(usage.charged(), dailyTokenQuota(),startOfDay.plus(1, ChronoUnit.DAYS),
                usage.known(),usage.unknown(),usage.reserved(),usage.unknownCalls());
    }

    /** {@code quota} 0 means unlimited. */
    public record DailyUsage(long tokensUsed, long quota, Instant resetsAt,long reportedTokens,long unknownTokens,long reservedTokens,long unknownCalls) {
    }

    private Instant startOfToday() {
        return clock.instant().truncatedTo(ChronoUnit.DAYS);
    }

    /** 0 means unlimited. */
    public long dailyTokenQuota() {
        return properties.getLimits().getDailyTokenQuota();
    }

    /** Counts this attempt and returns the total for the current minute (0 when Redis is unavailable). */
    private long turnsThisMinute(Long userId) {
        String key = RATE_KEY_PREFIX + userId + ":" + clock.millis() / 60_000;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1) redis.expire(key, Duration.ofMinutes(2));
            return count == null ? 0 : count;
        } catch (RuntimeException unavailable) {
            log.warn("usage_rate_limit_unavailable userId={} reason={}", userId, unavailable.getMessage());
            return 0;
        }
    }
}
