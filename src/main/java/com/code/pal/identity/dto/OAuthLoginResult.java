package com.code.pal.identity.dto;

/** Tokens for an OAuth sign-in, and whether this sign-in created the account. */
public record OAuthLoginResult(AuthResponse tokens, boolean newAccount) {
}
