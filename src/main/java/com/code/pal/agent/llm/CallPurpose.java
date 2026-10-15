package com.code.pal.agent.llm;

/** Why a model call was made (recorded in the call ledger). Stored as text, so new values need no schema change. */
public enum CallPurpose {
    /** The agent loop of a build, plan or chat run. */
    AGENT,
    SMALL_TALK,
    INTENT,
    VISION,
    REQUIREMENTS,
    PLANNER,
    SCHEMA_CORRECTION,
    UNIT,
    OTHER
}
