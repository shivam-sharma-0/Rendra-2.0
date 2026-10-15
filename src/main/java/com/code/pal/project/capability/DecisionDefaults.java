package com.code.pal.project.capability;

import com.code.pal.agent.intent.RequestRouter;
import com.code.pal.project.capability.BuildDecisions.Auth;
import com.code.pal.project.capability.BuildDecisions.Storage;

import java.util.regex.Pattern;

/**
 * Storage and sign-in a request implies, so the user is not asked what the request already says.
 * A null field means the user must decide (for example accounts or cloud data without a connected
 * backend); an explicit cloud or accounts request is never silently downgraded to browser storage.
 */
public final class DecisionDefaults {
    private static final String SIGN_IN = "(?:sign ?in|sign ?up|log ?in|login|logins|accounts?)";
    private static final Pattern NO_SIGN_IN = Pattern.compile("\\b(?:without|no|bina)\\s+(?:a |any )?" + SIGN_IN
            + "\\b|\\b" + SIGN_IN + "\\s+(?:nahi|mat|not needed|isn't needed|is not needed)\\b");
    private static final Pattern ACCOUNTS = Pattern.compile("\\b(?:" + SIGN_IN
            + "|multi-?user|each user|per user|user roles?|admin panel)\\b");
    private static final Pattern CLOUD = Pattern.compile("\\b(?:supabase|cloud|database|backend|server|online sync|"
            + "sync across devices|across devices|every device)\\b");
    /** Data other people see or edit together needs cloud storage even without sign-in. */
    private static final Pattern SHARED_DATA = Pattern.compile("\\b(?:shared\\s+(?:notes?|lists?|boards?|tasks?|data|"
            + "docs?|documents?|files?|calendars?|expenses?|todos?)|collaborat\\w*|real-?time|share (?:it |them )?with)\\b");
    private static final Pattern PERSISTENCE = Pattern.compile("\\b(?:save[sd]?|remember\\w*|persist\\w*|stored?|"
            + "history|to-?dos?|notes?|tasks?|tracker|track\\w*|diary|journal|bookmarks?|inventory|crud|records?|"
            + "entries|expenses?|budget|list of)\\b");

    public record Inferred(Storage storage, Auth auth) {
    }

    private DecisionDefaults() {
    }

    public static Inferred infer(String request, boolean supabaseConnected) {
        String signals = NO_SIGN_IN.matcher(RequestRouter.positiveSignals(request == null ? "" : request))
                .replaceAll(" ");
        boolean accounts = ACCOUNTS.matcher(signals).find();
        if (accounts || CLOUD.matcher(signals).find() || SHARED_DATA.matcher(signals).find()) {
            if (!supabaseConnected) {
                return new Inferred(null, null);
            }
            return new Inferred(Storage.SUPABASE, accounts ? Auth.ACCOUNTS : Auth.NONE);
        }
        if (PERSISTENCE.matcher(signals).find()) {
            return new Inferred(Storage.BROWSER, Auth.NONE);
        }
        return new Inferred(Storage.NONE, Auth.NONE);
    }
}
