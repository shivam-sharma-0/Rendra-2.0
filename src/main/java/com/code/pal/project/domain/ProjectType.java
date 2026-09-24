package com.code.pal.project.domain;

/**
 * How the user is working with the project — mirrors the composer modes in the SPA.
 *
 * <ul>
 *   <li>{@code BUILD} — the agent makes changes directly.</li>
 *   <li>{@code CHAT}  — free-form Q&amp;A / exploration, no edits.</li>
 *   <li>{@code PLAN}  — produce a detailed spec for a complex build.</li>
 * </ul>
 */
public enum ProjectType {
    BUILD,
    CHAT,
    PLAN
}
