package com.code.pal.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * Base type for all domain exceptions that map to a specific HTTP status.
 *
 * <p>Lives in {@code shared} so the {@link GlobalExceptionHandler} can translate any
 * module's failure without importing that module — keeping the dependency arrow pointing
 * from modules → shared (never the reverse). Each module subclasses this.
 */
public abstract class ApiException extends RuntimeException {

    protected ApiException(String message) {
        super(message);
    }

    /** HTTP status this failure maps to. */
    public abstract HttpStatus status();
}
