package com.code.pal.agent.domain;
/** Safe stable public reason; provider bodies and internal exception text are never public status. */
public enum AgentFailureReason {
    DAILY_QUOTA_EXCEEDED, RUN_TOKEN_BUDGET_EXCEEDED, RUN_LIMIT_REACHED,
    BUILD_VALIDATION_FAILED, PROVIDER_REQUEST_REJECTED, USER_REVIEW_REQUIRED, AGENT_FAILED
}
