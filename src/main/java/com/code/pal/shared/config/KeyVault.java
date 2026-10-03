package com.code.pal.shared.config;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM encryption seeded from a master passphrase via PBKDF2.
 * Wire format: base64( salt[16] || iv[12] || ciphertext+gcmTag )
 * Properties store this as: ENC(base64...)
 */
public final class KeyVault {

    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;

    private KeyVault() {}

    public static String encrypt(String plaintext, String passphrase) {
        try {
            byte[] salt = randomBytes(SALT_BYTES);
            byte[] iv = randomBytes(IV_BYTES);
            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[SALT_BYTES + IV_BYTES + ciphertext.length];
            System.arraycopy(salt, 0, result, 0, SALT_BYTES);
            System.arraycopy(iv, 0, result, SALT_BYTES, IV_BYTES);
            System.arraycopy(ciphertext, 0, result, SALT_BYTES + IV_BYTES, ciphertext.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public static String decrypt(String base64Ciphertext, String passphrase) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64Ciphertext);
            byte[] salt = new byte[SALT_BYTES];
            byte[] iv = new byte[IV_BYTES];
            byte[] ciphertext = new byte[raw.length - SALT_BYTES - IV_BYTES];
            System.arraycopy(raw, 0, salt, 0, SALT_BYTES);
            System.arraycopy(raw, SALT_BYTES, iv, 0, IV_BYTES);
            System.arraycopy(raw, SALT_BYTES + IV_BYTES, ciphertext, 0, ciphertext.length);
            SecretKey key = deriveKey(passphrase, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed — wrong master key or corrupted value", e);
        }
    }

    public static String decryptIfEncrypted(String value, String passphrase) {
        if (isEncrypted(value)) {
            return decrypt(value.substring(4, value.length() - 1), passphrase);
        }
        return value;
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith("ENC(") && value.endsWith(")");
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS);
        return new SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
