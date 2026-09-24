package com.code.pal.identity.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * On a failed / cancelled provider login, clean up the auth-request cookie and send the
 * browser back to the SPA with a generic error flag (no internal details leaked).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final HttpCookieOAuth2AuthorizationRequestRepository authRequestRepository;

    @Value("${app.oauth2.failure-redirect}")
    private String failureRedirect;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        authRequestRepository.removeAuthorizationRequest(request, response);
        log.warn("OAuth login failed: {}", exception.getMessage());

        String target = failureRedirect + "#error="
                + URLEncoder.encode("oauth_failed", StandardCharsets.UTF_8);
        getRedirectStrategy().sendRedirect(request, response, target);
    }
}
