package com.code.pal.agent.usage;

import com.code.pal.agent.config.AgentProperties;
import com.code.pal.agent.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Each model call's worst case is set aside in the daily quota before the call. */
class TokenReservationTest {

    private static final Long USER = 7L;
    private static final Instant NOW = Instant.parse("2026-10-03T05:30:00Z");

    private final AgentProperties properties = new AgentProperties();
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final InMemoryReservations store = new InMemoryReservations();
    private final AgentUsagePolicy policy =
            new AgentUsagePolicy(properties, runs, mock(StringRedisTemplate.class), Clock.fixed(NOW, ZoneOffset.UTC), store);

    private void usedToday(long tokens) {
        when(runs.sumTokensByUserIdSince(eq(USER), any())).thenReturn(tokens);
    }

    @Test
    void grantsACallThatFitsAndReleasesIt() {
        properties.getLimits().setDailyTokenQuota(10_000);
        usedToday(1_000);
        AgentUsagePolicy.Reservation reservation = policy.reserve(USER, 3_000);
        assertTrue(reservation.granted());
        assertEquals(3_000, store.total(USER));
        policy.release(reservation);
        assertEquals(0, store.total(USER));
    }

    @Test
    void refusesACallWhoseWorstCaseWouldCrossTheQuota() {
        properties.getLimits().setDailyTokenQuota(10_000);
        usedToday(8_000);
        AgentUsagePolicy.Reservation reservation = policy.reserve(USER, 3_000);
        assertFalse(reservation.granted());
        assertEquals(AgentUsagePolicy.QUOTA_LOW_MESSAGE, reservation.refusal());
        assertEquals(0, store.total(USER), "a refused reservation is not kept");
    }

    @Test
    void concurrentCallsCannotJointlyCrossTheQuota() {
        properties.getLimits().setDailyTokenQuota(10_000);
        usedToday(2_000);
        assertTrue(policy.reserve(USER, 5_000).granted());
        AgentUsagePolicy.Reservation second = policy.reserve(USER, 5_000);
        assertFalse(second.granted(), "2,000 used + 5,000 reserved + 5,000 more would cross 10,000");
        assertTrue(policy.reserve(USER, 3_000).granted());
    }

    @Test
    void aQuotaAlreadyUsedUpIsReportedAsSuch() {
        properties.getLimits().setDailyTokenQuota(10_000);
        usedToday(10_000);
        assertEquals(AgentUsagePolicy.QUOTA_MESSAGE, policy.reserve(USER, 1).refusal());
    }

    @Test
    void noQuotaMeansNoReservation() {
        properties.getLimits().setDailyTokenQuota(0);
        assertTrue(policy.reserve(USER, 1_000_000).granted());
        assertEquals(0, store.total(USER));
    }

    @Test
    void withoutRedisTheCallIsCheckedOnItsOwn() {
        properties.getLimits().setDailyTokenQuota(10_000);
        usedToday(2_000);
        AgentUsagePolicy broken = new AgentUsagePolicy(properties, runs, mock(StringRedisTemplate.class),
                Clock.fixed(NOW, ZoneOffset.UTC), new TokenReservations() {
                    @Override public long add(Long userId, String id, long tokens, Duration ttl) { throw new IllegalStateException("redis down"); }
                    @Override public void remove(Long userId, String id) { throw new IllegalStateException("redis down"); }
                });
        assertTrue(broken.reserve(USER, 5_000).granted());
        assertFalse(broken.reserve(USER, 9_000).granted());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisStoreSumsLiveReservationsAndDropsExpiredOnes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        Map<Object, Object> fields = new HashMap<>();
        when(redis.opsForHash()).thenReturn(hash);
        doAnswer(call -> fields.put(call.getArgument(1), call.getArgument(2))).when(hash).put(anyString(), any(), any());
        when(hash.entries(anyString())).thenAnswer(call -> new HashMap<>(fields));
        doAnswer(call -> fields.remove(call.getArgument(1)) == null ? 0L : 1L).when(hash).delete(anyString(), any());
        fields.put("old", "4000:" + (NOW.toEpochMilli() - 1));   // expired: left by a crashed process

        RedisTokenReservations reservations = new RedisTokenReservations(redis, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(1_500, reservations.add(USER, "a", 1_500, Duration.ofMinutes(2)));
        assertFalse(fields.containsKey("old"));
        assertEquals(2_000, reservations.add(USER, "b", 500, Duration.ofMinutes(2)));
    }

    /** Thread-safe in-memory store; reservations here never expire during a test. */
    private static final class InMemoryReservations implements TokenReservations {
        private final Map<String, Long> live = new ConcurrentHashMap<>();

        @Override
        public synchronized long add(Long userId, String id, long tokens, Duration ttl) {
            live.put(userId + ":" + id, tokens);
            return total(userId);
        }

        @Override
        public void remove(Long userId, String id) {
            live.remove(userId + ":" + id);
        }

        long total(Long userId) {
            return live.entrySet().stream().filter(e -> e.getKey().startsWith(userId + ":")).mapToLong(Map.Entry::getValue).sum();
        }
    }
}
