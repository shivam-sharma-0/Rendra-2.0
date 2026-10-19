package com.code.pal.agent.workspace;

/** Sanitized validation failure; generated SQL or ephemeral secrets never enter model diagnostics. */
public final class SupabaseScratchException extends RuntimeException {
    private final boolean schemaFailure;

    public SupabaseScratchException(String message, boolean schemaFailure) {
        super(message);
        this.schemaFailure = schemaFailure;
    }

    public boolean schemaFailure() {
        return schemaFailure;
    }
}
