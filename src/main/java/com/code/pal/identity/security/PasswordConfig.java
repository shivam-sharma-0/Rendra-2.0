package com.code.pal.identity.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The {@link PasswordEncoder} lives in its own config (not {@code SecurityConfig}) on
 * purpose: {@code AuthServiceImpl} needs the encoder, {@code SecurityConfig} needs the
 * OAuth success handler, and that handler needs {@code AuthService}. Keeping the encoder
 * here breaks what would otherwise be a bean-creation cycle through SecurityConfig.
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt cost 12 — strong yet acceptable latency on the login path.
        return new BCryptPasswordEncoder(12);
    }
}
