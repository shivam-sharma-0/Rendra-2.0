package com.code.pal.shared.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tunables for {@link RateLimitFilter}. Bound from {@code app.rate-limit.*}.
 *
 * <p>Defaults protect the sensitive auth endpoints (login/signup/refresh) against
 * brute-force/credential-stuffing without impacting normal usage.
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
@Getter
@Setter
public class RateLimitProperties {

    /** Master switch; disable in tests or trusted internal environments. */
    private boolean enabled = true;

    /** Max auth attempts allowed per client IP within {@link #authWindowSeconds}. */
    private int authRequests = 20;

    /** Rolling window length (seconds) for the auth bucket. */
    private int authWindowSeconds = 60;
    private boolean failOpen = false;
    /** Exact socket IPs of trusted proxies, walked from the right of the forwarded chain. */
    private java.util.List<String> trustedProxyIps = java.util.List.of();
}
