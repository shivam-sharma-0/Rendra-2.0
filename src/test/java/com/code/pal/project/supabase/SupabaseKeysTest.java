package com.code.pal.project.supabase;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupabaseKeysTest {
    private static String jwt(String payload) {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void secretAndServerRoleKeysNeverReachThePublicField() {
        for (String key : new String[] { "sb_secret_abc123", " SB_SECRET_abc123 ",
                jwt("{\"iss\":\"supabase\",\"role\":\"service_role\"}"), jwt("{\"role\":\" Supabase_Admin \"}"),
                jwt("{\"role\":\"postgres\"}") }) {
            var error = assertThrows(SupabaseException.class, () -> SupabaseKeys.requirePublic(key));
            assertFalse(error.getMessage().contains(key.strip()), "the key is never echoed back");
        }
    }

    @Test
    void publicKeysAndUnreadableValuesAreLeftForSupabaseToVerify() {
        assertDoesNotThrow(() -> SupabaseKeys.requirePublic(jwt("{\"iss\":\"supabase\",\"role\":\"anon\"}")));
        assertDoesNotThrow(() -> SupabaseKeys.requirePublic("sb_publishable_abc123"));
        assertDoesNotThrow(() -> SupabaseKeys.requirePublic("a.%%%.c"));
        assertDoesNotThrow(() -> SupabaseKeys.requirePublic(jwt("{\"role\":7}")));
    }
}
