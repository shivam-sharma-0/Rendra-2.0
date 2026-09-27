package com.code.pal.agent.context;

import java.util.List;
import java.util.Map;

/** Compact structural description produced without an LLM. */
public record ProjectMap(
        List<String> importantDirectories,
        List<ProjectFileSummary> importantFiles,
        List<String> manifests,
        List<String> frameworks,
        List<String> configurationFiles,
        List<String> routes,
        List<String> featureModules,
        Map<String, String> relevantDependencies,
        List<String> instructionFiles,
        int scannedFileCount,
        boolean scanTruncated
) {
}
