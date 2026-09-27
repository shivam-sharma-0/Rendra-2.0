package com.code.pal.agent.context;

import java.util.List;
import java.util.Map;

/** Debuggable account of what entered the context and what was left out. */
public record ContextDiagnostics(
        List<SelectedPath> selectedFiles,
        List<OmittedPath> omittedFiles,
        int approximateChars,
        int approximateTokens,
        int budgetChars,
        int omittedFileCount,
        int omittedConversationMessages,
        Map<String, Integer> omissionCounts
) {
    public record SelectedPath(String path, ContextSelectionReason reason, int approximateChars) {
    }

    public record OmittedPath(String path, String reason) {
    }
}
