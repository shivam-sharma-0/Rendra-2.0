package com.code.pal.agent.dto;

/** One issue found by the security scan. Severity is HIGH | MEDIUM | LOW. */
public record SecurityFinding(String severity, String title, String file, String detail) {
}
