package com.code.pal.identity.security;

import com.code.pal.shared.security.AuthPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authenticates each request from its Bearer access token — statelessly.
 *
 * <p>The token is verified and the principal is reconstructed entirely from its
 * claims: <b>no database access per request</b>. An invalid/missing token simply
 * leaves the context unauthenticated; the endpoint's access rules then decide.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final AuthSessions sessions;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                var claims = jwtService.parse(header.substring(BEARER.length()));

                // Refresh tokens must never authorize a business call.
                if (JwtService.ACCESS.equals(jwtService.tokenType(claims)) && sessions.accessAllowed(claims)) {
                    AuthPrincipal principal = jwtService.toPrincipal(claims);
                    var authority = new SimpleGrantedAuthority("ROLE_" + principal.role());
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null, List.of(authority));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | IllegalArgumentException e) {
                // Malformed / expired / bad signature — stay unauthenticated.
                log.debug("Rejected invalid access token");
            }
        }

        filterChain.doFilter(request, response);
    }
}
