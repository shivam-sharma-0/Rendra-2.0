package com.code.pal.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets the application stores in its database (for example a user's Supabase access
 * token) with AES-256-GCM ({@link KeyVault}) under {@code APP_DATA_KEY}. Without that key nothing secret
 * can be stored, so features that need it say so instead of storing plaintext.
 */
@Component
public class DataCipher {
    private final String key;

    public DataCipher(@Value("${app.data-key:}") String key) {
        this.key = key == null ? "" : key.trim();
    }

    public boolean available() {
        return key.length() >= 16;
    }

    public String encrypt(String plaintext) {
        requireKey();
        return KeyVault.encrypt(plaintext, key);
    }

    public String decrypt(String ciphertext) {
        requireKey();
        return KeyVault.decrypt(ciphertext, key);
    }

    private void requireKey() {
        if (!available()) {
            throw new IllegalStateException("APP_DATA_KEY is not set (at least 16 characters): secrets cannot be stored");
        }
    }
}
