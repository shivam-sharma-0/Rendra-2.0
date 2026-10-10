package com.code.pal.agent.usage;

import java.time.Duration;

/**
 * Tokens set aside for model calls that are still running, per user. A reservation expires on its own,
 * so one left behind by a crashed process stops counting after its time-to-live.
 */
public interface TokenReservations {

    /** Adds a reservation and returns the user's total reserved tokens, this one included. */
    long add(Long userId, String reservationId, long tokens, Duration timeToLive);

    void remove(Long userId, String reservationId);
}
