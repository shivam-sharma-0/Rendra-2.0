package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when an invite token can't be accepted (expired, used, revoked, or wrong email). Maps to 410 Gone. */
public class InviteInvalidException extends ApiException {

    public InviteInvalidException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.GONE;
    }
}
