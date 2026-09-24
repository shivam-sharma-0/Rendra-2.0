package com.code.pal.shared.exception;

import java.time.Instant;

/** Uniform error body returned for every handled failure. */
public record ApiError(int status, String message, Instant timestamp) {

    public static ApiError of(int status, String message) {
        return new ApiError(status, message, Instant.now());
    }
}
