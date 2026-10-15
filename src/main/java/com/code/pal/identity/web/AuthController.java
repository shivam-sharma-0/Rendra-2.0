package com.code.pal.identity.web;

import com.code.pal.identity.dto.AuthResponse;
import com.code.pal.identity.dto.LoginRequest;
import com.code.pal.identity.dto.SessionResponse;
import com.code.pal.identity.dto.SignUpRequest;
import com.code.pal.identity.dto.UserResponse;
import com.code.pal.identity.exception.InvalidCredentialsException;
import com.code.pal.identity.security.AuthCookieService;
import com.code.pal.identity.service.AuthService;
import com.code.pal.shared.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Auth endpoints. The refresh token is issued and consumed only via an HttpOnly cookie
 * (see {@link AuthCookieService}); the SPA only ever sees the short-lived access token.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AuthCookieService cookies;

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse signup(@Valid @RequestBody SignUpRequest request, HttpServletResponse response) {
        return issue(authService.signup(request), response);
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return issue(authService.login(request), response);
    }

    /** Rotate the access token using the refresh cookie — no request body. */
    @PostMapping("/refresh")
    public SessionResponse refresh(
            @CookieValue(name = AuthCookieService.REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidCredentialsException("Missing refresh token");
        }
        return issue(authService.refresh(refreshToken), response);
    }

    /** Clear the refresh cookie. Idempotent — safe to call without a session. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@CookieValue(name=AuthCookieService.REFRESH_COOKIE,required=false) String refreshToken,HttpServletResponse response) {
        authService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    }

    /** Echoes the caller identified by the access token — handy for verifying auth end to end. */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(new UserResponse(
                principal.publicId().toString(), principal.email(), null, principal.role()));
    }

    /** Set the refresh cookie and return the access token + user in the body. */
    private SessionResponse issue(AuthResponse auth, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookies.build(auth.refreshToken()).toString());
        return SessionResponse.from(auth);
    }
}
