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
 *   <li>prompt + completion tokens per UTC day (database: run totals plus ledger calls outside any run
 *       total — intent, vision, requirements), with each model call's worst case reserved
 *       before the call ({@link #reserve}) so concurrent calls cannot jointly cross the quota.</li>
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
    private final TokenReservations reservations;
    /** Model calls outside any run total; null in focused tests (only run totals count then). */
    private final ModelCallRepository calls;

    @Autowired
    public AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, ModelCallRepository calls) {
        this(properties, runs, redis, Clock.systemUTC(), new RedisTokenReservations(redis, Clock.systemUTC()), calls);
    }

    AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, Clock clock) {
        this(properties, runs, redis, clock, new RedisTokenReservations(redis, clock));
    }

    AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, Clock clock,
                     TokenReservations reservations) {
        this(properties, runs, redis, clock, reservations, null);
    }

    AgentUsagePolicy(AgentProperties properties, AgentRunRepository runs, StringRedisTemplate redis, Clock clock,
                     TokenReservations reservations, ModelCallRepository calls) {
        this.calls = calls;
        this.properties = properties;
        this.runs = runs;
        this.redis = redis;
        this.clock = clock;
        this.reservations = reservations;
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
    /** When the quota is not used up but cannot cover the next model call's worst case. */
    public static final String QUOTA_LOW_MESSAGE = "Not enough of today's AI quota is left for this request. It resets at 00:00 UTC.";

    /**
     * Sets aside {@code estimatedTokens} (the call's input estimate plus its output cap) for a model call,
     * counting every other call of the user still running. Refused when today's use plus everything
     * reserved would cross the quota. Release it once the call's real usage is saved.
     */
    public Reservation reserve(Long userId, long estimatedTokens) {
        long quota = dailyTokenQuota();
        if (quota <= 0) return Reservation.UNLIMITED;
        long used = tokensUsedToday(userId);
        if (used >= quota) return Reservation.refused(QUOTA_MESSAGE);
        long tokens = Math.max(1, estimatedTokens);
        String id = java.util.UUID.randomUUID().toString();
        long reserved;
        try {
            reserved = reservations.add(userId, id, tokens, reservationTimeToLive());
        } catch (RuntimeException unavailable) {
            // Without Redis the call is still checked on its own, as before reservations existed.
            log.warn("usage_reservation_unavailable userId={} reason={}", userId, unavailable.getMessage());
            return used + tokens > quota ? Reservation.refused(QUOTA_LOW_MESSAGE) : new Reservation(userId, null, tokens, true, null);
        }
        if (used + reserved > quota) {
            release(new Reservation(userId, id, tokens, true, null));
            log.info("usage_limit userId={} limit=daily_tokens_reserved used={} reserved={} quota={}", userId, used, reserved, quota);
            return Reservation.refused(QUOTA_LOW_MESSAGE);
        }
        return new Reservation(userId, id, tokens, true, null);
    }

    /** Ends a reservation (safe for refused, unlimited or already released ones). */
    public void release(Reservation reservation) {
        if (reservation == null || reservation.id() == null) return;
        try {
            reservations.remove(reservation.userId(), reservation.id());
        } catch (RuntimeException unavailable) {
            log.debug("usage_reservation_release_failed userId={} reason={}", reservation.userId(), unavailable.getMessage());
        }
    }

    /** A reservation outlives the longest model call, so one left by a crash stops counting soon after. */
    private Duration reservationTimeToLive() {
        return Duration.ofMillis(Math.max(10_000, properties.getLlm().getCallBudgetMs()) + 60_000);
    }

    /** A model call's share of the daily quota; {@code granted} false carries the message to show. */
    public record Reservation(Long userId, String id, long tokens, boolean granted, String refusal) {
        public static final Reservation UNLIMITED = new Reservation(null, null, 0, true, null);

        public static Reservation refused(String message) {
            return new Reservation(null, null, 0, false, message);
        }
    }

    /** Tokens the user has consumed since 00:00 UTC today: their runs, plus model calls outside any run total. */
    public long tokensUsedToday(Long userId) {
        return usedSince(userId, startOfToday());
    }

    private long usedSince(Long userId, Instant since) {
        long inRuns = runs.sumTokensByUserIdSince(userId, since);
        return calls == null ? inRuns : inRuns + calls.sumUncountedTokensByUserIdSince(userId, since);
    }

    /** Today's token use against the daily quota, and when the count resets (next 00:00 UTC). */
    public DailyUsage dailyUsage(Long userId) {
        Instant startOfDay = startOfToday();
        return new DailyUsage(usedSince(userId, startOfDay), dailyTokenQuota(),
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
