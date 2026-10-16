package com.code.pal.project.supabase;

import java.util.Optional;
import java.util.UUID;

/**
 * The one rule for whether a project's Supabase connection may be used to build or publish: a connection to the
 * operator's shared instance is usable only where the operator explicitly enabled its SQL path. Build decisions
 * and publishing share it so neither can drift from the other.
 */
public final class SupabaseAdmission {
    public static final String SELF_HOSTED_BLOCKED = "This app is connected to the server's shared Supabase instance, which is "
            + "disabled here. Connect your own Supabase project, then build or publish again.";

    private SupabaseAdmission() {
    }

    /** Null when the project may use its connection (or has none); otherwise the reason to show. */
    public static String problem(SupabaseConnectionRepository connections, SelfHostedSupabase selfHosted, UUID projectId) {
        if (connections == null) {
            return null;
        }
        Optional<SupabaseConnectionRepository.Capability> connection = connections.capability(projectId);
        return connection.isPresent() && !usable(connection.get().getMode(), selfHosted) ? SELF_HOSTED_BLOCKED : null;
    }

    public static boolean usable(SupabaseConnection.Mode mode, SelfHostedSupabase selfHosted) {
        return mode != SupabaseConnection.Mode.SELF_HOSTED || selfHosted != null && selfHosted.operatorSqlEnabled();
    }
}
