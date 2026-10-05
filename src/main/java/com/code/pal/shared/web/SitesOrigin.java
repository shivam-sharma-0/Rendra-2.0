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
 *
 * <p>With {@code app.sites.wildcard-domain} set, every site gets its own host under that domain instead
 * of a path: {@code <slug>.<domain>} (published), {@code pv--<key>.<domain>} (private preview) and
 * {@code dr--<key>.<domain>} (a running build's draft). A site then runs at the root of its own origin,
 * so client-side routers and absolute paths work, and one project's code cannot read another's storage.
 * Slugs never contain "--", so the labels cannot collide. Production should use a domain on the public
 * suffix list, so sites cannot set cookies for each other.
 */
@Component
public class SitesOrigin {

    public static final String PREVIEW_LABEL = "pv--";
    public static final String DRAFT_LABEL = "dr--";

    private final String baseUrl;
    private final String host;
    private final int port;
    private final String scheme;
    /** Lowercase, without dots at either end; empty when sites are served under paths. */
    private final String wildcardDomain;

    public SitesOrigin(String baseUrl) {
        this(baseUrl, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SitesOrigin(@Value("${app.sites.base-url}") String baseUrl,
                       @Value("${app.sites.wildcard-domain:}") String wildcardDomain) {
        URI uri = URI.create(baseUrl.trim());
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw new IllegalStateException("app.sites.base-url must be an absolute URL, e.g. http://127.0.0.1:8080");
        }
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.host = uri.getHost().toLowerCase(Locale.ROOT);
        this.port = uri.getPort() != -1 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        this.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        this.wildcardDomain = wildcardDomain == null ? "" : wildcardDomain.trim().toLowerCase(Locale.ROOT).replaceAll("(^\\.+|\\.+$)", "");
    }

    public boolean hostPerSite() { return !wildcardDomain.isEmpty(); }

    /** The private preview of a project, at the root of its own host (host-per-site mode only). */
    public String previewHostUrl(String previewKey) { return siteUrl(PREVIEW_LABEL + previewKey); }

    /** A running build's draft of a project (host-per-site mode only). */
    public String draftHostUrl(String previewKey) { return siteUrl(DRAFT_LABEL + previewKey); }

    private String siteUrl(String label) {
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + label + "." + wildcardDomain + (defaultPort ? "" : ":" + port) + "/";
    }

    /** Whether the request is for the wildcard domain itself or any host under it (on any port). */
    public boolean underWildcardDomain(HttpServletRequest request) {
        if (wildcardDomain.isEmpty()) return false;
        String requestHost = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        return requestHost.equals(wildcardDomain) || requestHost.endsWith("." + wildcardDomain);
    }

    /**
     * The site label of a request to {@code <label>.<wildcard domain>} on the sites port, or null when the
     * request is not for a per-site host. Only one label: nested hosts are not sites.
     */
    public String siteLabel(HttpServletRequest request) {
        if (wildcardDomain.isEmpty() || request.getServerPort() != port) return null;
        String requestHost = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        String suffix = "." + wildcardDomain;
        if (!requestHost.endsWith(suffix)) return null;
        String label = requestHost.substring(0, requestHost.length() - suffix.length());
        return label.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?") ? label : null;
    }

    /** Trailing slash: built sites reference their assets relatively ({@code ./assets/...}). */
    public String previewUrl(String token) { return baseUrl + "/preview/" + token + "/"; }

    public String publishedUrl(String slug) { return hostPerSite() ? siteUrl(slug) : baseUrl + "/p/" + slug + "/"; }

    /** Absolute URL of {@code path} (which must start with '/') on the sites origin. */
    public String resolve(String path) { return baseUrl + path; }

    public boolean isSitesRequest(HttpServletRequest request) {
        return host.equalsIgnoreCase(request.getServerName()) && request.getServerPort() == port;
    }

    public static boolean isSitesPath(String uri) {
        return uri.equals("/p") || uri.startsWith("/p/") || uri.equals("/preview") || uri.startsWith("/preview/");
    }
}
