package com.code.pal.identity.service.impl;

import com.code.pal.identity.domain.AuthProvider;
import com.code.pal.identity.domain.User;
import com.code.pal.identity.dto.AuthResponse;
import com.code.pal.identity.dto.LoginRequest;
import com.code.pal.identity.dto.OAuthLoginResult;
import com.code.pal.identity.dto.SignUpRequest;
import com.code.pal.identity.dto.UserResponse;
import com.code.pal.identity.exception.EmailAlreadyExistsException;
import com.code.pal.identity.exception.InvalidCredentialsException;
import com.code.pal.identity.repository.UserRepository;
import com.code.pal.identity.security.JwtService;
import com.code.pal.identity.service.AuthService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Core auth logic. Credentials are checked in-process (no {@code AuthenticationManager}
 * round-trip) and tokens are stateless, so the login path is a single indexed read plus
 * one BCrypt compare.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public AuthResponse signup(SignUpRequest request) {
        String email = normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        User user = userRepository.save(User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .name(trimToNull(request.name()))
                .build());

        log.info("User registered id={} email={}", user.getId(), email);
        return tokensFor(user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = normalize(request.email());
        User user = userRepository.findByEmail(email).orElse(null);

        // Uniform failure for unknown user / wrong password → no user enumeration.
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login for {}", email);
            throw new InvalidCredentialsException("Invalid email or password");
        }
        if (!user.getEnabled()) {
            throw new InvalidCredentialsException("Account is disabled");
        }

        user.setLastLoginAt(Instant.now());   // flushed on tx commit; no extra save() needed
        log.info("Login ok id={} email={}", user.getId(), email);
        return tokensFor(user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        final Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidCredentialsException("Invalid or expired refresh token");
        }
        if (!JwtService.REFRESH.equals(jwtService.tokenType(claims))) {
            throw new InvalidCredentialsException("Not a refresh token");
        }

        // Reload the user so a fresh access token reflects the current role / enabled state.
        User user = userRepository.findByEmail(claims.getSubject())
                .filter(User::getEnabled)
                .orElseThrow(() -> new InvalidCredentialsException("User not found or disabled"));

        return tokensFor(user);
    }

    @Override
    @Transactional
    public OAuthLoginResult oauthLogin(String email, String name, AuthProvider provider) {
        String normalized = normalize(email);

        // One indexed read; provision on first sight, otherwise just touch last-login.
        User user = userRepository.findByEmail(normalized).orElse(null);
        boolean newAccount = user == null;
        if (newAccount) {
            user = userRepository.save(User.builder()
                    .email(normalized)
                    .provider(provider)
                    .name(trimToNull(name))
                    .emailVerified(true)          // the provider already verified the address
                    .lastLoginAt(Instant.now())
                    .build());
            log.info("OAuth signup id={} email={} provider={}", user.getId(), normalized, provider);
        } else {
            if (!user.getEnabled()) {
                throw new InvalidCredentialsException("Account is disabled");
            }
            user.setLastLoginAt(Instant.now());   // flushed on tx commit; no extra save()
            log.info("OAuth login id={} email={} provider={}", user.getId(), normalized, provider);
        }
        return new OAuthLoginResult(tokensFor(user), newAccount);
    }

    private AuthResponse tokensFor(User user) {
        return new AuthResponse(
                jwtService.generateAccessToken(user),
                jwtService.generateRefreshToken(user),
                UserResponse.from(user));
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
