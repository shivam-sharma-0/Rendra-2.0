package com.code.pal.project.supabase;

import org.springframework.http.HttpStatus;

/** A stable, credential-free migration admission/recovery error. */
public final class SupabaseMigrationException extends SupabaseException {
    private final String code;

    public SupabaseMigrationException(String code) {
        super(code + ": review the migration operation before retrying");
        this.code = code;
    }

    public String code() { return code; }

    @Override
    public HttpStatus status() { return HttpStatus.CONFLICT; }
}
