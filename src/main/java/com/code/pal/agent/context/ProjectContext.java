package com.code.pal.agent.context;

import java.util.List;

/** Initial budgeted context plus the trace that progressive tools update during the run. */
public record ProjectContext(
        ProjectMap projectMap,
        String projectContextMessage,
        List<ContextMessage> conversation,
        ContextDiagnostics diagnostics,
        ProjectContextTrace trace
) {
}
