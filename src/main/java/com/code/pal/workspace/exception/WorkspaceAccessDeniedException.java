package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when the caller is not a member of the workspace they are trying to use. */
public class WorkspaceAccessDeniedException extends ApiException {

    public WorkspaceAccessDeniedException() {
        super("You do not have access to this workspace");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }
}
