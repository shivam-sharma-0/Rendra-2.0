package com.code.pal.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/projects/p/agent-runs/r/staged-files");

    @Test
    void aBrowserThatHungUpIsNotAServerError() {
        var aborted = new RuntimeException("Could not write JSON",
                new AsyncRequestNotUsableException("ServletOutputStream failed to write", new IOException("connection aborted")));
        assertNull(handler.handleUnexpected(aborted, request), "nothing can be sent to a closed connection");
    }

    @Test
    void realFailuresStillAnswerWithAGenericServerError() {
        var response = handler.handleUnexpected(new IllegalStateException("boom"), request);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }
}
