package com.code.pal.workspace.domain;

/**
 * A member's permission level within a workspace.
 *
 * <ul>
 *   <li>{@code OWNER}  — created the workspace; full control incl. delete.</li>
 *   <li>{@code ADMIN}  — manage members and settings (reserved for the teams flow).</li>
 *   <li>{@code MEMBER} — create and work on projects.</li>
 * </ul>
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MEMBER
}
