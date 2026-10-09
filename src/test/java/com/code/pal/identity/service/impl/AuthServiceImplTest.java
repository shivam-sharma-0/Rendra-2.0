package com.code.pal.identity.service.impl;

import com.code.pal.identity.domain.AuthProvider;
import com.code.pal.identity.domain.User;
import com.code.pal.identity.dto.OAuthLoginResult;
import com.code.pal.identity.repository.UserRepository;
import com.code.pal.identity.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private final UserRepository users = mock(UserRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final AuthServiceImpl auth = new AuthServiceImpl(users, mock(PasswordEncoder.class), jwt);

    AuthServiceImplTest() {
        when(jwt.generateAccessToken(any())).thenReturn("access");
        when(jwt.generateRefreshToken(any())).thenReturn("refresh");
    }

    @Test
    void firstOAuthSignInCreatesTheAccountAndSaysSo() {
        when(users.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OAuthLoginResult result = auth.oauthLogin(" New@Example.com ", "New Person", AuthProvider.GOOGLE);

        assertTrue(result.newAccount());
        assertEquals("new@example.com", result.tokens().user().email());
        assertEquals("refresh", result.tokens().refreshToken());
        verify(users).save(any(User.class));
    }

    @Test
    void laterOAuthSignInIsNotANewAccount() {
        User existing = User.builder().email("old@example.com").provider(AuthProvider.GOOGLE).build();
        when(users.findByEmail("old@example.com")).thenReturn(Optional.of(existing));
        Instant before = Instant.now();

        OAuthLoginResult result = auth.oauthLogin("old@example.com", "Old Person", AuthProvider.GOOGLE);

        assertFalse(result.newAccount());
        assertNotNull(existing.getLastLoginAt());
        assertFalse(existing.getLastLoginAt().isBefore(before));
        verify(users, never()).save(any(User.class));
    }
}
