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
import static org.mockito.ArgumentMatchers.any;
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
    private final DailyTokenBudget budget = mock(DailyTokenBudget.class);
    private final AgentUsagePolicy policy =
            new AgentUsagePolicy(properties, runs, redis, Clock.fixed(NOW, ZoneOffset.UTC), budget);

    AgentUsagePolicyTest() {
        when(budget.usage(eq(USER), any())).thenReturn(new DailyTokenBudget.Usage(0,0,0,0,0));
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
        when(budget.usage(eq(USER), any())).thenReturn(new DailyTokenBudget.Usage(1_000,0,1_000,0,0));

        UsageLimitExceededException rejected = assertThrows(UsageLimitExceededException.class,
                () -> policy.requireAdmitted(USER));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.status());
        assertEquals(AgentUsagePolicy.QUOTA_MESSAGE, rejected.getMessage());
    }

    @Test
    void dailyUsageReportsTokensQuotaAndNextMidnightUtc() {
        properties.getLimits().setDailyTokenQuota(50_000);
        when(budget.usage(eq(USER), any())).thenReturn(new DailyTokenBudget.Usage(1_234,0,1_234,0,0));

        AgentUsagePolicy.DailyUsage usage = policy.dailyUsage(USER);

        assertEquals(1_234L, usage.tokensUsed());
        assertEquals(50_000L, usage.quota());
        assertEquals(Instant.parse("2026-10-04T00:00:00Z"), usage.resetsAt());
    }

    @Test
    void dailyUsageReportsZeroQuotaWhenUnlimited() {
        properties.getLimits().setDailyTokenQuota(0);

        assertEquals(0L, policy.dailyUsage(USER).quota());
    }
    @Test void dailyResponseDistinguishesReportedUnknownAndActiveCapacity() {
        properties.getLimits().setDailyTokenQuota(1000);
        when(budget.usage(eq(USER),any())).thenReturn(new DailyTokenBudget.Usage(300,200,100,200,2));
        var response=com.code.pal.agent.dto.DailyUsageResponse.from(policy.dailyUsage(USER));
        assertEquals(300,response.tokensUsed());assertEquals(100,response.reportedTokens());assertEquals(200,response.unknownTokens());
        assertEquals(200,response.reservedTokens());assertEquals(500,response.capacityUsed());assertEquals(500,response.remainingTokens());
        properties.getLimits().setDailyTokenQuota(0);
        assertEquals(null,com.code.pal.agent.dto.DailyUsageResponse.from(policy.dailyUsage(USER)).remainingTokens());
    }

    @Test
    void quotaCountsFromMidnightUtc() {
        policy.tokensUsedToday(USER);

        verify(budget).usage(USER, java.time.LocalDate.of(2026,10,3));
    }

    @Test
    void rejectsWhenTooManyRunsAreInProgress() {
        properties.getLimits().setMaxActiveRunsPerUser(2);
        when(runs.countByUserIdAndStatusIn(eq(USER), anyCollection())).thenReturn(2L);

        UsageLimitExceededException rejected = assertThrows(UsageLimitExceededException.class,
                () -> policy.requireAdmitted(USER));

        assertEquals("You already have 2 requests in progress. Wait for one to finish, or stop it, before sending another.",
                rejected.getMessage());
        verify(runs).countByUserIdAndStatusIn(USER, List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.COMMITTING));
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
