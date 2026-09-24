package com.code.pal.project.domain;

/**
 * Lifecycle of a project's live deployment.
 *
 * <ul>
 *   <li>{@code NONE}    — never deployed.</li>
 *   <li>{@code PENDING} — a deploy is in flight.</li>
 *   <li>{@code LIVE}    — deployed and reachable at {@code publishedUrl}.</li>
 *   <li>{@code FAILED}  — the last deploy failed.</li>
 * </ul>
 */
public enum DeploymentStatus {
    NONE,
    PENDING,
    LIVE,
    FAILED
}
