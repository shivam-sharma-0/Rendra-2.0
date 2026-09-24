package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a project id does not exist, is soft-deleted, or belongs to another user.
 *
 * <p>Deliberately 404 (not 403) for the not-owned case — a 403 would confirm the id exists,
 * leaking information. From the caller's side the row simply isn't there.
 */
public class ProjectNotFoundException extends ApiException {

    public ProjectNotFoundException() {
        super("Project not found");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
