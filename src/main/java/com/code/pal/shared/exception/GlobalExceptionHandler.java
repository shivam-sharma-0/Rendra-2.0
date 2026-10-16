package com.code.pal.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Translates exceptions into a uniform {@link ApiError} body with the right HTTP status.
 *
 * <p>Domain failures carry their own status via {@link ApiException}. Framework/validation/data
 * failures are mapped explicitly so clients never see a raw 500 for a foreseeable error, and the
 * server never leaks internal details (stack traces stay in the logs).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Any module exception that declares its own status. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest request) {
        return build(ex.status(), ex.getMessage(), request);
    }

    /** Bean-validation failures on @Valid request bodies → 400 with all field errors. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .distinct()
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isBlank() ? "Validation failed" : message, request);
    }

    /** Validation on @RequestParam/@PathVariable (method-level constraints) → 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /** Malformed/unreadable JSON body → 400 (never echo the parser's internals). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body", request);
    }

    /** Wrong query-param type or a missing required param → 400. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequestParams(Exception ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request parameters", request);
    }

    /** Unique-constraint / FK / other integrity clashes → 409 (don't leak the SQL detail). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return build(HttpStatus.CONFLICT, "This action conflicts with existing data", request);
    }

    /** Concurrent update lost the optimistic-lock race → 409 (client should retry). */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "The resource changed while you were editing it. Please retry.", request);
    }

    /** Method-security / access checks that reach the advice → 403. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "You don't have access to this resource", request);
    }

    /** Safety net for any code still throwing ResponseStatusException — honour its status. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        return build(status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR,
                ex.getReason() != null ? ex.getReason() : "Request failed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        if (clientDisconnected(ex)) {
            // The browser cancelled the request (poll, refresh, navigation); nothing failed and nothing can be sent.
            log.debug("Client disconnected during {}", request.getRequestURI());
            return null;
        }
        log.error("Unhandled exception on {}", request.getRequestURI(), ex); // full stack in logs, generic to client
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", request);
    }

    private static boolean clientDisconnected(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String type = cause.getClass().getSimpleName();
            if (type.equals("ClientAbortException") || type.equals("AsyncRequestNotUsableException")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ApiError.of(status, message, request.getRequestURI()));
    }
}
