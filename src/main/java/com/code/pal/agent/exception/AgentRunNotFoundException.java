package com.code.pal.agent.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when an agent run does not exist within the given project. Maps to 404. */
public class AgentRunNotFoundException extends ApiException {

    public AgentRunNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
