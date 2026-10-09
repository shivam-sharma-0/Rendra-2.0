package com.code.pal.identity.security;

import com.code.pal.identity.domain.AuthProvider;
import com.code.pal.identity.dto.AuthResponse;
import com.code.pal.identity.dto.OAuthLoginResult;
import com.code.pal.identity.dto.UserResponse;
import com.code.pal.identity.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2SuccessHandlerTest {

    private static final String CALLBACK = "http://localhost:5173/oauth/callback";

    private final AuthService auth = mock(AuthService.class);
    private final AuthCookieService cookies = mock(AuthCookieService.class);
    private final OAuth2SuccessHandler handler =
            new OAuth2SuccessHandler(auth, mock(HttpCookieOAuth2AuthorizationRequestRepository.class), cookies);

    OAuth2SuccessHandlerTest() {
        ReflectionTestUtils.setField(handler, "successRedirect", CALLBACK);
        ReflectionTestUtils.setField(handler, "failureRedirect", "http://localhost:5173/auth");
        when(cookies.build("refresh")).thenReturn(ResponseCookie.from("rt", "refresh").build());
    }

    @Test
    void firstSignInRedirectsWithTheNewAccountFlag() throws Exception {
        MockHttpServletResponse response = signIn(true);

        assertEquals(CALLBACK + "?new=1", response.getRedirectedUrl());
        assertTrue(response.getHeader(HttpHeaders.SET_COOKIE).startsWith("rt=refresh"));
    }

    @Test
    void returningSignInRedirectsWithoutTheFlag() throws Exception {
        MockHttpServletResponse response = signIn(false);

        assertEquals(CALLBACK, response.getRedirectedUrl());
    }

    private MockHttpServletResponse signIn(boolean newAccount) throws Exception {
        AuthResponse tokens = new AuthResponse("access", "refresh", new UserResponse("id", "a@example.com", "A", "USER"));
        when(auth.oauthLogin("a@example.com", "A", AuthProvider.GOOGLE)).thenReturn(new OAuthLoginResult(tokens, newAccount));
        var authorities = List.of(new SimpleGrantedAuthority("OAUTH2_USER"));
        var user = new DefaultOAuth2User(authorities, Map.of("email", "a@example.com", "name", "A"), "email");
        var authentication = new OAuth2AuthenticationToken(user, authorities, "google");

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        return response;
    }
}
