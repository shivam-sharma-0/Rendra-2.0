package com.code.pal.agent.context;

import com.code.pal.agent.workspace.ProjectFiles;
import java.util.List;
import java.util.UUID;

/** Metadata only. A result never grants write authority or proves that a body has been read. */
public interface ProjectRetrieval {
    Result search(ProjectFiles files, UUID projectId, String root, String query, int limit);
    record Match(String path, String version, long sizeBytes, boolean asset, boolean summaryPartial,
                 List<String> symbols, List<String> imports, List<String> importedBy) {}
    record Result(String snapshotDigest, int scannedFiles, boolean partial, List<Match> matches) {}
}
