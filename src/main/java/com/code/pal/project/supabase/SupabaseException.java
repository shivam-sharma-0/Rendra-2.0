package com.code.pal.project.supabase;

import com.code.pal.shared.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Supabase rejected what the user supplied, or a migration failed there; the message says why. */
public class SupabaseException extends ApiException {
    public SupabaseException(String message) { super(message); }
    @Override public HttpStatus status() { return HttpStatus.BAD_REQUEST; }
}
