package com.code.pal.agent.dto;

import java.util.List;

/** Result of a real static scan over the project's workspace files. */
public record SecurityScanResult(
        int scannedFiles,
        int high,
        int medium,
        int low,
        List<SecurityFinding> findings
) {
}
