package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;
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

/**
 * Per-user agent limits, checked before a turn is accepted and again before every model call:
 * <ul>
 *   <li>turns per minute (Redis fixed window; fails open, like the auth limiter, if Redis is down),</li>
 *   <li>runs queued or running at once (database),</li>
 *   <li>prompt + completion tokens per UTC day (database).</li>
 * </ul>
 * The active-run count only sees runs already created, so two posts in the same instant can both pass
 * it; the per-minute window is what stops a burst.
 */
@Slf4j
@Component
public class AgentUsagePolicy implements AgentTurnAdmission {

    private static final List<RunStatus> ACTIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING);
    private static final String RATE_KEY_PREFIX = "agent:turns:";

    private final AgentProperties properties;
    private final AgentRunRepository runs;
    private final StringRedisTemplate redis;
    private final Clock clock;

    @Autowired
    public AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis) {
        this(properties, runs, redis, Clock.systemUTC());
    }

    AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, Clock clock) {
        this.properties = properties;
        this.runs = runs;
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public void requireAdmitted(Long userId) {
        AgentProperties.Limits limits = properties.getLimits();
        long quota = limits.getDailyTokenQuota();
        if (quota > 0 && tokensUsedToday(userId) >= quota) {
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

    /** Tokens the user's runs have consumed since 00:00 UTC today. */
    public long tokensUsedToday(Long userId) {
        return runs.sumTokensByUserIdSince(userId, startOfToday());
    }

    /** Today's token use against the daily quota, and when the count resets (next 00:00 UTC). */
    public DailyUsage dailyUsage(Long userId) {
        Instant startOfDay = startOfToday();
        return new DailyUsage(runs.sumTokensByUserIdSince(userId, startOfDay), dailyTokenQuota(),
                startOfDay.plus(1, ChronoUnit.DAYS));
    }

    /** {@code quota} 0 means unlimited. */
    public record DailyUsage(long tokensUsed, long quota, Instant resetsAt) {
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
