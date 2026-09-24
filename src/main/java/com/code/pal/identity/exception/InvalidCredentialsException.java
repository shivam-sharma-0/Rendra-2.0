package com.code.pal.identity.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown for bad login, disabled account, or an invalid/expired token. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNAUTHORIZED;
    }
}
