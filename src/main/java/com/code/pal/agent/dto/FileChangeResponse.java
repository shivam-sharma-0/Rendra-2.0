package com.code.pal.agent.dto;

import com.code.pal.agent.change.FileChange;
import com.code.pal.agent.change.FileOperation;
import java.time.Instant;
import java.util.UUID;

public record FileChangeResponse(UUID id, String path, String sourcePath, FileOperation operation,
                                 String beforeVersion, String afterVersion, Instant timestamp) {
    public static FileChangeResponse from(FileChange change) {
        return new FileChangeResponse(change.getId(), change.getPath(), change.getSourcePath(), change.getOperation(),
                change.getBeforeVersion(), change.getAfterVersion(), change.getCreatedAt());
    }
}
