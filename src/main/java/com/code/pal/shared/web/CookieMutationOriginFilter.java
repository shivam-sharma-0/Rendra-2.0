package com.code.pal.shared.web;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Cookie-authorized mutations need an explicit allowed browser origin; bearer routes remain separate. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+9)
public final class CookieMutationOriginFilter extends OncePerRequestFilter {
    private final Set<String> origins;
    public CookieMutationOriginFilter(@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> origins){this.origins=Set.copyOf(origins);}
    protected boolean shouldNotFilter(HttpServletRequest request){return !"POST".equals(request.getMethod()) || !Set.of("/api/auth/refresh","/api/auth/logout").contains(request.getRequestURI());}
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String origin=request.getHeader("Origin");
        if(origin==null || !origins.contains(origin)){response.setStatus(403);response.setContentType("application/json");response.getWriter().write("{\"message\":\"A trusted origin is required\"}");return;}
        chain.doFilter(request,response);
    }
}
