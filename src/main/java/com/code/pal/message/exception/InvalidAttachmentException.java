package com.code.pal.message.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** An attachment reference that is not an uploaded file of this project. */
public class InvalidAttachmentException extends ApiException {
    public InvalidAttachmentException(String message) { super(message); }
    @Override public HttpStatus status() { return HttpStatus.BAD_REQUEST; }
}
