package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when an invited email has no account, or a member handle doesn't exist. */
public class MemberNotFoundException extends ApiException {

    public MemberNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
