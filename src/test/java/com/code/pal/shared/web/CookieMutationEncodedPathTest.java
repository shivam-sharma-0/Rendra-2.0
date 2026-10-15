package com.code.pal.shared.web;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CookieMutationEncodedPathTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/%72efresh", "/api/auth/%6Cogout", "/api/%61uth/refresh",
            "/api/auth/refresh;session=x", "/api/auth/logout;session=x"})
    void encodedCookieMutationsRequireTheTrustedOrigin(String path) throws Exception {
        for (String origin : List.of("missing", "https://foreign.example", "http://localhost:5173")) {
            var request = new MockHttpServletRequest("POST", path);
            if (!origin.equals("missing")) {
                request.addHeader("Origin", origin);
            }
            var response = new MockHttpServletResponse();
            var called = new AtomicBoolean();
            new CookieMutationOriginFilter(List.of("http://localhost:5173"))
                    .doFilter(request, response, (req, res) -> called.set(true));
            boolean trusted = origin.equals("http://localhost:5173");
            assertEquals(trusted, called.get());
            assertEquals(trusted ? 200 : 403, response.getStatus());
        }
    }
}
