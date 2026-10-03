package com.code.pal.shared.usage;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A user hit an agent usage limit. Maps to 429; the message is safe to show to the user. */
public class UsageLimitExceededException extends ApiException {

    public UsageLimitExceededException(String message) {
        super(message);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.TOO_MANY_REQUESTS;
    }
}
