package com.code.pal.agent.llm;

/** Why a model call was made (recorded in the call ledger). Stored as text, so new values need no schema change. */
public enum CallPurpose {
    /** The agent loop of a build, plan or chat run. */
    AGENT,
    REPAIR,
    SMALL_TALK,
    VISION,
    VISUAL_REVIEW,
    REQUIREMENTS,
    PLANNER,
    SCHEMA_CORRECTION,
    UNIT,
    UNIT_CORRECTION,
    OTHER
}
