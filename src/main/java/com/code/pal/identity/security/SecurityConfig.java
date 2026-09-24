package com.code.pal.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. No sessions, no CSRF (there are no browser cookies to
 * protect — auth travels in the Authorization header). The custom filter runs
 * before the username/password filter and is the sole authenticator.
 *
 * <p>Google login rides on {@code oauth2Login} but stays session-less: the in-flight
 * authorization request is parked in a short-lived cookie, and on success our own
 * JWTs are minted and handed to the SPA (see {@link OAuth2SuccessHandler}).
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthRequestRepository;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;
    private final OAuth2FailureHandler oAuth2FailureHandler;

    /** Endpoints reachable without a token (incl. the OAuth login + callback routes). */
    private static final String[] PUBLIC_URLS = {
            "/api/auth/signup",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/oauth2/**",
            "/login/oauth2/**",
            // WebSocket handshake — authenticated by JwtHandshakeInterceptor (token in query),
            // not the Bearer filter, so the filter chain must let the upgrade request through.
            "/realtime"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_URLS).permitAll()
                        .anyRequest().authenticated())
                // Google (and future providers) — no server session; cookie-parked auth request.
                .oauth2Login(oauth -> oauth
                        .authorizationEndpoint(a -> a.authorizationRequestRepository(cookieAuthRequestRepository))
                        .successHandler(oAuth2SuccessHandler)
                        .failureHandler(oAuth2FailureHandler))
                // Return a clean 401 (not a redirect) for unauthenticated API calls.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, e) -> res.sendError(HttpServletResponse.SC_FORBIDDEN)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
