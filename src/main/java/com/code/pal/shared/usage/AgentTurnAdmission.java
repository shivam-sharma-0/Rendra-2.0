package com.code.pal.shared.usage;

/**
 * Gate in front of every user action that starts an agent turn (posting a message, creating a project
 * with a first prompt, approving requirements). It lives in {@code shared} so those modules can ask
 * without importing the agent module, which owns the implementation and the usage data.
 */
public interface AgentTurnAdmission {

    /**
     * @throws UsageLimitExceededException when the user is over the turn rate, the number of runs in
     *                                     progress, or the daily token quota
     */
    void requireAdmitted(Long userId);
}
