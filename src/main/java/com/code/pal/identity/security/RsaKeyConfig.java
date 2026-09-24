package com.code.pal.identity.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Loads the RSA key pair used to sign/verify JWTs (RS256).
 *
 * <p>Only this module signs with the private key; any future service verifies with the
 * public key alone — so splitting out a service never requires sharing a secret.
 */
@Configuration
public class RsaKeyConfig {

    @Bean
    public RSAPrivateKey jwtPrivateKey(@Value("${jwt.rsa.private-key}") Resource privateKeyPem) throws IOException {
        try (InputStream in = privateKeyPem.getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        }
    }

    @Bean
    public RSAPublicKey jwtPublicKey(@Value("${jwt.rsa.public-key}") Resource publicKeyPem) throws IOException {
        try (InputStream in = publicKeyPem.getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        }
    }
}
