package com.code.pal.workspace.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when a workspace id does not exist or is soft-deleted. */
public class WorkspaceNotFoundException extends ApiException {

    public WorkspaceNotFoundException() {
        super("Workspace not found");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
