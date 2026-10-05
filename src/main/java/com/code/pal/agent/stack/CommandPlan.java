package com.code.pal.agent.stack;

import com.code.pal.agent.validation.ValidationOperation;

import java.util.List;

/** A backend-chosen validation command, or why the operation is unavailable for this project. */
public record CommandPlan(ValidationOperation operation, boolean available, String packageManager, String script,
                          List<String> command, String unavailableReason) {

    public static CommandPlan unavailable(ValidationOperation operation, StackCapabilities capabilities, String reason) {
        return new CommandPlan(operation, false, capabilities.packageManager(), null, List.of(), reason);
    }
}
