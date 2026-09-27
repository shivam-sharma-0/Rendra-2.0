package com.code.pal.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

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
            // Liveness/readiness probes must be reachable without a token (metrics/prometheus stay authenticated).
            "/actuator/health",
            "/actuator/health/**",
            // Published project sites are public by design (served read-only, LIVE projects only).
            "/p/**",
            // WebSocket handshake — authenticated by JwtHandshakeInterceptor (token in query),
            // not the Bearer filter, so the filter chain must let the upgrade request through.
            "/realtime"
    };

    /** Browser origins allowed to call the REST API (comma-separated). Dev SPA by default. */
    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JWT: no server session cookie to protect, so CSRF is disabled. CORS is
                // explicit (origin allow-list below) since the SPA is served from a different origin.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Defence-in-depth response headers. This is a JSON API (no HTML), so the CSP is locked
                // right down; HSTS/anti-clickjacking/nosniff still matter for any browser-rendered error.
                .headers(headers -> headers
                        // Framing + CSP are set per-path (below), so disable the built-in ones here.
                        .frameOptions(frame -> frame.disable())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        // JSON API stays locked down; published sites under /p/** get a permissive CSP so
                        // the served app can run its own inline scripts/styles and be framed by the SPA.
                        .addHeaderWriter((request, response) -> {
                            if (request.getRequestURI().startsWith("/p/")) {
                                response.setHeader("Content-Security-Policy",
                                        "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob: https:; frame-ancestors 'self' "
                                                + String.join(" ", allowedOrigins));
                            } else {
                                response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
                                response.setHeader("X-Frame-Options", "DENY");
                            }
                        }))
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

    /**
     * CORS for the SPA. Credentials are enabled (the refresh flow rides an HttpOnly cookie), so the
     * origin list must be explicit — wildcard origins are incompatible with credentials.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type",
                "X-Request-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
