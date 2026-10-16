package com.code.pal.project.supabase;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * The key field ends up in the published site, so it must hold a public key. A secret key there would hand every
 * visitor full database access. Decoding a JWT here is only a reason to reject it, never proof that it is valid:
 * Supabase itself still verifies the key.
 */
public final class SupabaseKeys {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SERVER_ROLES = Set.of("service_role", "supabase_admin", "postgres");
    public static final String SECRET_KEY = "This is a secret server key. Paste the anon (public) key from the Supabase API settings; "
            + "a secret key would give every visitor full access to your database";

    private SupabaseKeys() {
    }

    /** Whether a value is a Supabase secret or server-role key, which must never be published. */
    public static boolean secret(String key) {
        String value = key == null ? "" : key.strip();
        return value.toLowerCase(Locale.ROOT).startsWith("sb_secret_") || isServerRole(role(value));
    }

    static void requirePublic(String key) {
        if (secret(key)) {
            throw new SupabaseException(SECRET_KEY);
        }
    }

    private static boolean isServerRole(String role) {
        return role != null && SERVER_ROLES.contains(role);
    }

    private static String role(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 3 || parts[1].isEmpty() || parts[1].length() > 4_000) {
            return null;
        }
        try {
            JsonNode payload = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return payload.path("role").isString() ? payload.path("role").asString().strip().toLowerCase(Locale.ROOT) : null;
        } catch (RuntimeException notAJwt) {
            return null;
        }
    }
}
