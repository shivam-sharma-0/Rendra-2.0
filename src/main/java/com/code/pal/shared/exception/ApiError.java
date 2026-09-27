package com.code.pal.shared.exception;

import com.code.pal.shared.web.ApiConstants;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * Uniform error body returned for every handled failure.
 *
 * <p>Carries the numeric {@code status} and its {@code error} reason phrase, a human-readable
 * {@code message}, the request {@code path}, the {@code requestId} correlation id (so a client
 * and the server logs can be cross-referenced), and a UTC {@code timestamp}.
 */
public record ApiError(
        int status,
        String error,
        String message,
        String path,
        String requestId,
        Instant timestamp) {

    public static ApiError of(HttpStatus status, String message, String path) {
        return new ApiError(
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                MDC.get(ApiConstants.REQUEST_ID_MDC_KEY),
                Instant.now());
    }
}
