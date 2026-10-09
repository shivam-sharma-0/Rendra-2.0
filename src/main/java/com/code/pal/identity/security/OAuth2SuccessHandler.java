package com.code.pal.identity.security;

import com.code.pal.identity.domain.AuthProvider;
import com.code.pal.identity.dto.OAuthLoginResult;
import com.code.pal.identity.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

/**
 * On a successful provider login: exchange the verified identity for our own JWT pair
 * (JIT-provisioning the account), drop the refresh token into an HttpOnly cookie, and bounce
 * the browser back to the SPA — no tokens in the URL. The SPA then calls {@code /api/auth/refresh}
 * (cookie in hand) to obtain its access token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthService authService;
    private final HttpCookieOAuth2AuthorizationRequestRepository authRequestRepository;
    private final AuthCookieService cookies;

    @Value("${app.oauth2.success-redirect}")
    private String successRedirect;

    @Value("${app.oauth2.failure-redirect}")
    private String failureRedirect;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        authRequestRepository.removeAuthorizationRequest(request, response);

        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String email = principal.getAttribute("email");
        String name = principal.getAttribute("name");

        if (email == null || email.isBlank()) {
            log.warn("OAuth success but provider returned no email");
            getRedirectStrategy().sendRedirect(request, response,
                    failureRedirect + "#error=" + enc("email_unavailable"));
            return;
        }

        AuthProvider provider = resolveProvider(authentication);
        OAuthLoginResult result = authService.oauthLogin(email, name, provider);

        // Refresh token → HttpOnly cookie; the SPA fetches its access token via /api/auth/refresh.
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.build(result.tokens().refreshToken()).toString());
        // A first sign-in is flagged so the SPA can start onboarding. The flag grants nothing.
        String target = result.newAccount()
                ? UriComponentsBuilder.fromUriString(successRedirect).queryParam("new", "1").build().toUriString()
                : successRedirect;
        getRedirectStrategy().sendRedirect(request, response, target);
    }

    private static AuthProvider resolveProvider(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && "google".equals(token.getAuthorizedClientRegistrationId())) {
            return AuthProvider.GOOGLE;
        }
        return AuthProvider.GOOGLE;   // only Google is registered today
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
