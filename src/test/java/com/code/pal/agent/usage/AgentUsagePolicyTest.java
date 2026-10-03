package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.domain.RunStatus;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.shared.usage.UsageLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentUsagePolicyTest {

    private static final Long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-03T05:30:00Z");

    private final AgentProperties properties = new AgentProperties();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AtomicLong counter = new AtomicLong();
    private final AgentUsagePolicy policy =
            new AgentUsagePolicy(properties, runs, redis, Clock.fixed(NOW, ZoneOffset.UTC));

    AgentUsagePolicyTest() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenAnswer(invocation -> counter.incrementAndGet());
    }

    @Test
    void admitsAUserWithinEveryLimit() {
        assertDoesNotThrow(() -> policy.requireAdmitted(USER));
    }

    @Test
    void rejectsOnceTheDailyTokenQuotaIsUsed() {
        properties.getLimits().setDailyTokenQuota(1_000);
        when(runs.sumTokensByUserIdSince(eq(USER), eq(Instant.parse("2026-10-03T00:00:00Z")))).thenReturn(1_000L);

        UsageLimitExceededException rejected = assertThrows(UsageLimitExceededException.class,
                () -> policy.requireAdmitted(USER));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.status());
        assertEquals(AgentUsagePolicy.QUOTA_MESSAGE, rejected.getMessage());
    }

    @Test
    void quotaCountsFromMidnightUtc() {
        policy.tokensUsedToday(USER);

        verify(runs).sumTokensByUserIdSince(USER, Instant.parse("2026-10-03T00:00:00Z"));
    }

    @Test
    void rejectsWhenTooManyRunsAreInProgress() {
        properties.getLimits().setMaxActiveRunsPerUser(2);
        when(runs.countByUserIdAndStatusIn(eq(USER), anyCollection())).thenReturn(2L);

        UsageLimitExceededException rejected = assertThrows(UsageLimitExceededException.class,
                () -> policy.requireAdmitted(USER));

        assertEquals("You already have 2 requests in progress. Wait for one to finish, or stop it, before sending another.",
                rejected.getMessage());
        verify(runs).countByUserIdAndStatusIn(USER, List.of(RunStatus.QUEUED, RunStatus.RUNNING));
    }

    @Test
    void rejectsTheTurnAfterThePerMinuteLimit() {
        properties.getLimits().setTurnsPerMinute(3);

        for (int i = 0; i < 3; i++) policy.requireAdmitted(USER);

        assertThrows(UsageLimitExceededException.class, () -> policy.requireAdmitted(USER));
    }

    @Test
    void rateLimitFailsOpenWhenRedisIsDown() {
        when(values.increment(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertDoesNotThrow(() -> policy.requireAdmitted(USER));
    }

    @Test
    void zeroDisablesEveryCheck() {
        properties.getLimits().setDailyTokenQuota(0);
        properties.getLimits().setMaxActiveRunsPerUser(0);
        properties.getLimits().setTurnsPerMinute(0);

        assertDoesNotThrow(() -> policy.requireAdmitted(USER));

        verifyNoInteractions(runs);
        verifyNoInteractions(values);
    }

    @Test
    void rateWindowKeyExpires() {
        policy.requireAdmitted(USER);

        verify(redis).expire(eq("agent:turns:7:" + NOW.toEpochMilli() / 60_000), eq(java.time.Duration.ofMinutes(2)));
    }
}
