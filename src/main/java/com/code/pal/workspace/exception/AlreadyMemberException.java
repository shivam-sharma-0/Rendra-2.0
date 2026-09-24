package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when inviting someone who is already a member of the workspace. */
public class AlreadyMemberException extends ApiException {

    public AlreadyMemberException() {
        super("That person is already a member");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.CONFLICT;
    }
}
