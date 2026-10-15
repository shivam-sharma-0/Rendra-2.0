package com.code.pal.identity.service;

import com.code.pal.identity.domain.AuthProvider;
import com.code.pal.identity.dto.AuthResponse;
import com.code.pal.identity.dto.OAuthLoginResult;
import com.code.pal.identity.dto.LoginRequest;
import com.code.pal.identity.dto.SignUpRequest;

/** Authentication use-cases: register, authenticate, and rotate access via refresh token. */
public interface AuthService {

    AuthResponse signup(SignUpRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(String refreshToken);
    void logout(String refreshToken);

    /**
     * Authenticate a verified OAuth identity, provisioning the account on first sight
     * (just-in-time). Returns the same token pair as local login, and whether the account was
     * created by this sign-in.
     *
     * @param email    verified email from the provider (the account key)
     * @param name     display name from the provider, may be {@code null}
     * @param provider the federated provider that vouched for this identity
     */
    OAuthLoginResult oauthLogin(String email, String name, AuthProvider provider);
}
