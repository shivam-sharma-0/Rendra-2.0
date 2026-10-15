package com.code.pal.message.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

public class MessageConflictException extends ApiException {
    public MessageConflictException() { super("This message id has already been used for a different request"); }
    @Override public HttpStatus status() { return HttpStatus.CONFLICT; }
}
