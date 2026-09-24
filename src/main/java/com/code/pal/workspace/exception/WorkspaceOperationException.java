package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A workspace action that violates a business rule (e.g. owner leaving, deleting personal). */
public class WorkspaceOperationException extends ApiException {

    public WorkspaceOperationException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
