package com.code.pal.project.supabase;

import org.springframework.http.HttpStatus;

/** Operator policy refusal, before opening a connection or executing tenant SQL. */
public final class SelfHostedSqlDisabledException extends SupabaseException {
    public static final String CODE = "SELF_HOSTED_SQL_DISABLED";

    public SelfHostedSqlDisabledException() {
        super(CODE + ": Self-hosted SQL is disabled on this server. Connect a user-owned Supabase project.");
    }

    public String code() {
        return CODE;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }
}
