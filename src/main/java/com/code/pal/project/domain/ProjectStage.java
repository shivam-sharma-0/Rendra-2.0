package com.code.pal.project.domain;

/** User-visible lifecycle gate for a generated application. */
public enum ProjectStage {
    REQUIREMENTS,
    DESIGN,
    BUILDING,
    REVIEW,
    DEPLOYING,
    LIVE,
    FAILED
}
