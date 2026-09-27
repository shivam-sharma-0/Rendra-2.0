package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when sharing a project with someone who already has access. Maps to 409. */
public class AlreadyProjectMemberException extends ApiException {

    public AlreadyProjectMemberException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
