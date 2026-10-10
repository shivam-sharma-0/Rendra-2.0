package com.code.pal.agent.usage;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reservations as one Redis hash per user ({@code agent:reserve:<userId>}): field = reservation id,
 * value = {@code tokens:expiresAtMillis}. Expired fields are dropped when the total is read; the key
 * itself expires after the longest reservation.
 */
class RedisTokenReservations implements TokenReservations {

    private static final String KEY_PREFIX = "agent:reserve:";

    private final StringRedisTemplate redis;
    private final Clock clock;

    RedisTokenReservations(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public long add(Long userId, String reservationId, long tokens, Duration timeToLive) {
        String key = KEY_PREFIX + userId;
        long now = clock.millis();
        HashOperations<String, String, String> hash = redis.opsForHash();
        hash.put(key, reservationId, tokens + ":" + (now + timeToLive.toMillis()));
        redis.expire(key, timeToLive);
        long total = 0;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, String> entry : hash.entries(key).entrySet()) {
            String[] parts = entry.getValue().split(":", 2);
            long amount = parse(parts[0]);
            long expiresAt = parts.length > 1 ? parse(parts[1]) : 0;
            if (expiresAt <= now) expired.add(entry.getKey());
            else total += amount;
        }
        for (String field : expired) hash.delete(key, field);
        return total;
    }

    @Override
    public void remove(Long userId, String reservationId) {
        redis.opsForHash().delete(KEY_PREFIX + userId, reservationId);
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
