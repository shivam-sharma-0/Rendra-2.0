package com.code.pal.agent.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when an operation is not valid for the run's current state (e.g. rollback/cancel). Maps to 409. */
public class AgentRunConflictException extends ApiException {

    public AgentRunConflictException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
