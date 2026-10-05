package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A project environment variable the user must correct (name format, value, count). */
public class InvalidEnvironmentVariableException extends ApiException {
    public InvalidEnvironmentVariableException(String message) { super(message); }
    @Override public HttpStatus status() { return HttpStatus.BAD_REQUEST; }
}
