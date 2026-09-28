package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class WorkflowStageException extends ApiException {
    public WorkflowStageException(String message) { super(message); }
    @Override public HttpStatus status() { return HttpStatus.CONFLICT; }
}
