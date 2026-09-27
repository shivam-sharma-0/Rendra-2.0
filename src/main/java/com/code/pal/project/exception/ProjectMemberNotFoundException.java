package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when a project collaborator (share) does not exist for the given project. Maps to 404. */
public class ProjectMemberNotFoundException extends ApiException {

    public ProjectMemberNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
