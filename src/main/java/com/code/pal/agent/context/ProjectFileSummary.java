package com.code.pal.agent.context;

import java.util.List;

/** Deterministic file metadata; it intentionally contains no full source content. */
public record ProjectFileSummary(
        String path,
        String language,
        String category,
        long sizeBytes,
        int approximateLines,
        List<String> imports,
        List<String> exports
) {
}
