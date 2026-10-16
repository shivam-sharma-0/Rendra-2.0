package com.code.pal.project.supabase;

import com.code.pal.shared.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Connect a project to the user's Supabase project, and review and apply the migrations the agent wrote. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/projects/{projectId}/supabase")
public class SupabaseController {
    private final SupabaseService supabase;

    @GetMapping
    public SupabaseService.Status status(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return supabase.status(principal.userId(), projectId);
    }

    @PutMapping
    public SupabaseService.Status connect(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                          @RequestBody ConnectRequest request) {
        return supabase.connect(principal.userId(), projectId, request.url(), request.anonKey(), request.accessToken());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        supabase.disconnect(principal.userId(), projectId);
    }

    @GetMapping("/migrations")
    public List<SupabaseService.Migration> migrations(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return supabase.migrations(principal.userId(), projectId);
    }

    /** The exact SQL and manifest digest the user approves; any later change to files or connection invalidates it. */
    @GetMapping("/migrations/review")
    public SupabaseService.MigrationReview review(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId) {
        return supabase.review(principal.userId(), projectId);
    }

    /**
     * The user's approval of one reviewed manifest. The client keeps the same Idempotency-Key across retries of
     * this approval, so a lost response never runs the SQL twice. No body means a legacy client: refused.
     */
    @PostMapping("/migrations/apply")
    public SupabaseService.ApplyResult apply(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                             @RequestBody(required = false) ApplyRequest request,
                                             @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if (request == null) return supabase.apply(principal.userId(), projectId);
        return supabase.apply(principal.userId(), projectId, request.expectedManifestHash(), idempotencyKey);
    }

    @GetMapping("/migrations/operations/{operationId}")
    public SupabaseService.ApplyResult operation(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                                 @PathVariable UUID operationId) {
        return supabase.operation(principal.userId(), projectId, operationId);
    }

    /** Status after a lost response, found by the approval's Idempotency-Key. */
    @GetMapping("/migrations/operations")
    public SupabaseService.ApplyResult operationByKey(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable UUID projectId,
                                                      @RequestParam("key") String idempotencyKey) {
        return supabase.operationByKey(principal.userId(), projectId, idempotencyKey);
    }

    public record ApplyRequest(String expectedManifestHash) {}

    /** {@code accessToken}: optional personal access token, needed only to apply migrations from here. */
    public record ConnectRequest(String url, String anonKey, String accessToken) {}
}
