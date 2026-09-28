package com.code.pal.project.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class RequirementApprovalRequiredException extends ApiException {
    public RequirementApprovalRequiredException() { super("Complete and approve project requirements before building"); }
    @Override public HttpStatus status() { return HttpStatus.CONFLICT; }
}
