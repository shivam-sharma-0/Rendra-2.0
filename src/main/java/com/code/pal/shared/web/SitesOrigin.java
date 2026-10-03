package com.code.pal.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

/**
 * The separate origin that serves user-generated sites: agent previews ({@code /preview/**}) and
 * published projects ({@code /p/**}). It must differ from the API by HOST, not only by port —
 * browsers scope cookies by host, so a different port would still receive the API's cookies.
 *
 * <p>Dev default {@code http://127.0.0.1:8080} works because the SPA and API use {@code localhost}.
 * Production uses a dedicated domain (behind a proxy, enable {@code server.forward-headers-strategy}
 * so the request host/port reflect the public URL).
 */
@Component
public class SitesOrigin {

    private final String baseUrl;
    private final String host;
    private final int port;

    public SitesOrigin(@Value("${app.sites.base-url}") String baseUrl) {
        URI uri = URI.create(baseUrl.trim());
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw new IllegalStateException("app.sites.base-url must be an absolute URL, e.g. http://127.0.0.1:8080");
        }
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.host = uri.getHost().toLowerCase(Locale.ROOT);
        this.port = uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    public String previewUrl(String token) { return baseUrl + "/preview/" + token; }

    public String publishedUrl(String slug) { return baseUrl + "/p/" + slug; }

    /** Absolute URL of {@code path} (which must start with '/') on the sites origin. */
    public String resolve(String path) { return baseUrl + path; }

    public boolean isSitesRequest(HttpServletRequest request) {
        return host.equalsIgnoreCase(request.getServerName()) && request.getServerPort() == port;
    }

    public static boolean isSitesPath(String uri) {
        return uri.equals("/p") || uri.startsWith("/p/") || uri.equals("/preview") || uri.startsWith("/preview/");
    }
}
