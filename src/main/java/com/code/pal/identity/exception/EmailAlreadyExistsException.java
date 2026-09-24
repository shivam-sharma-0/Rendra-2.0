package com.code.pal.identity.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when signing up with an email that is already registered. */
public class EmailAlreadyExistsException extends ApiException {

    public EmailAlreadyExistsException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
