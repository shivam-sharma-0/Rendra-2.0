package com.code.pal.agent.context;

/** Why a path was included in or discovered by an agent's context. */
public enum ContextSelectionReason {
    USER_MENTIONED,
    OPEN_FILE,
    PROJECT_MANIFEST,
    PROJECT_CONFIGURATION,
    PROJECT_INSTRUCTIONS,
    ROUTE,
    ENTRY_POINT,
    FEATURE_MODULE,
    SEARCH_MATCH,
    DIRECT_IMPORT,
    FILE_SUMMARY,
    FILE_READ,
    DIRECTORY_DISCOVERY
}
