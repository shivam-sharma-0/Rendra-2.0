package com.code.pal.message.exception;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Raised when a message does not exist within the given project. Maps to 404. */
public class MessageNotFoundException extends ApiException {

    public MessageNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
