package com.code.pal.shared.web;

/**
 * Cross-cutting HTTP/API constants shared by every module's web layer.
 *
 * <p>Lives in {@code shared} so controllers and filters reference a single source of truth
 * instead of re-declaring magic numbers/strings (DRY). Never holds mutable state.
 */
public final class ApiConstants {

    private ApiConstants() {
    }

    /** Hard cap on page size so a client can never request an unbounded result set. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Default page size when a list endpoint receives no explicit {@code size}. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Correlation-id header echoed to clients and threaded through logs (MDC). */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** SLF4J MDC key under which the correlation id is stored for the request thread. */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    /** Optional client-supplied key used to make non-GET mutations idempotent. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
}
