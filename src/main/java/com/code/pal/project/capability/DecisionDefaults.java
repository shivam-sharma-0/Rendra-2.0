package com.code.pal.project.capability;

import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.project.capability.BuildDecisions.Auth;
import com.code.pal.project.capability.BuildDecisions.Storage;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * Storage and sign-in a request implies, so the user is not asked what the request already says.
 * A null field means the user must decide (for example accounts or cloud data without a connected
 * backend); an explicit cloud or accounts request is never silently downgraded to browser storage.
 */
public final class DecisionDefaults {
    private static final Map<String, Pattern> SIGNALS = loadSignals();

    public record Inferred(Storage storage, Auth auth) {
    }

    private DecisionDefaults() {
    }

    public static Inferred infer(String request, boolean supabaseConnected) {
        return infer(request, new BuildDecisions.Available(false, supabaseConnected ? "connected" : null), null);
    }

    public static Inferred infer(String request, BuildDecisions.Available available, CapabilityProposal proposal) {
        String original = RequestRouter.normalize(request == null ? "" : request);
        String signals = SIGNALS.get("noAccounts").matcher(RequestRouter.positiveSignals(original))
                .replaceAll(" ");
        boolean noAccounts = has("noAccounts", original) || has("accounts", original) && !has("accounts", signals);
        boolean accounts = has("accounts", signals) || !noAccounts && proposal != null && proposal.accounts();
        boolean deniedServer = has("server", original) && !has("server", signals);
        if (deniedServer && has("staticOnly", original) && !accounts) {
            return new Inferred(Storage.NONE, Auth.NONE);
        }
        if (has("localOnly", original) || deniedServer) {
            // An explicit scope restriction takes priority over both a semantic proposal and business keywords.
            return accounts ? new Inferred(null, null) : new Inferred(Storage.BROWSER, Auth.NONE);
        }
        boolean persistent = has("persistent", signals)
                || proposal != null && proposal.persistence() != CapabilityProposal.Persistence.NONE;
        boolean server = persistent || accounts || has("server", signals) || has("mern", signals) || has("shared", signals);
        if (server) {
            if (has("supabase", signals) && !available.supabaseConnected()
                    || has("mern", signals) && !available.mernConfigured()) {
                return new Inferred(null, null);
            }
            Storage storage = has("mern", signals) ? Storage.MERN
                    : available.supabaseConnected() ? Storage.SUPABASE
                    : available.mernConfigured() ? Storage.MERN : null;
            return storage == null ? new Inferred(null, null)
                    : new Inferred(storage, accounts ? Auth.ACCOUNTS : Auth.NONE);
        }
        return new Inferred(Storage.NONE, Auth.NONE);
    }

    private static boolean has(String name, String text) {
        return SIGNALS.get(name).matcher(text).find();
    }

    private static Map<String, Pattern> loadSignals() {
        try (InputStream input = DecisionDefaults.class.getResourceAsStream("/policies/capability-signals.json")) {
            if (input == null) throw new IllegalStateException("Capability signal policy is missing");
            var root = new ObjectMapper().readTree(input);
            return root.properties().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                    entry -> Pattern.compile(entry.getValue().asString())));
        } catch (IOException invalid) {
            throw new IllegalStateException("Capability signal policy cannot be read", invalid);
        }
    }
}
