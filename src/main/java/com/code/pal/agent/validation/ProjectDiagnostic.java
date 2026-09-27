package com.code.pal.agent.validation;

/** Bounded, provider-neutral compiler/build/test diagnostic. */
public record ProjectDiagnostic(String file, Integer line, Integer column, DiagnosticSeverity severity,
                                String message, String source, String code) {
    public String fingerprint() {
        return String.join("|", file == null ? "" : file, line == null ? "" : line.toString(),
                column == null ? "" : column.toString(), severity.name(), message, source,
                code == null ? "" : code);
    }
}
