package com.code.pal.shared.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The Content-Security-Policy of user-generated sites: the app may run its own inline scripts and
 * styles and call https APIs (plus the operator's self-hosted Supabase, often plain http locally),
 * and only the app itself and the SPA may frame it.
 */
@Component
public class SiteContentPolicy {
    private final String header;

    public SiteContentPolicy(@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins,
                             @Value("${app.supabase.self-hosted.url:}") String selfHostedSupabase) {
        String origin = selfHostedSupabase == null ? "" : selfHostedSupabase.trim().replaceAll("/+$", "");
        String supabase = origin.isEmpty() ? "" : " " + origin + " " + origin.replaceFirst("^http", "ws");
        this.header = "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob: https: wss:" + supabase
                + "; frame-ancestors 'self' " + String.join(" ", allowedOrigins);
    }

    public String header() {
        return header;
    }
}
