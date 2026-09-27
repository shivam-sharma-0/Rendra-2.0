package com.code.pal.project.domain;

/**
 * A collaborator's role on a single project (independent of workspace membership).
 * OWNER/EDITOR may change the project; VIEWER has read-only access to preview, code and history.
 */
public enum ProjectRole {
    OWNER,
    EDITOR,
    VIEWER
}
