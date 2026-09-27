package com.code.pal.notification.domain;

/** Kinds of activity a user can be notified about. */
public enum NotificationType {
    BUILD_FINISHED,
    BUILD_FAILED,
    PROJECT_SHARED,
    WORKSPACE_INVITE,
    ROLE_CHANGED
}
