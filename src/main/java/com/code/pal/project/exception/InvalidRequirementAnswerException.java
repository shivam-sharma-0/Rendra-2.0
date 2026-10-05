package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A requirement answer the user must correct (for example, an empty answer to a required question). */
public class InvalidRequirementAnswerException extends ApiException {
    public InvalidRequirementAnswerException(String message) { super(message); }
    @Override public HttpStatus status() { return HttpStatus.BAD_REQUEST; }
}
