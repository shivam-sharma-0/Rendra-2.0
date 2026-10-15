package com.code.pal.agent.stack;

import tools.jackson.databind.JsonNode;
import java.util.List;

/** Compare dependency declarations, treating omitted and empty sections equally. */
public final class NpmLockfiles {
    private NpmLockfiles() {}
    public static boolean matches(JsonNode manifest, JsonNode root) {
        if (!root.isObject()) return false;
        for (String section : List.of("dependencies", "devDependencies", "optionalDependencies")) {
            var before = manifest.path(section); var after = root.path(section);
            boolean beforeEmpty = before.isMissingNode() || before.isObject() && before.isEmpty();
            boolean afterEmpty = after.isMissingNode() || after.isObject() && after.isEmpty();
            if (!(beforeEmpty && afterEmpty) && !before.equals(after)) return false;
        }
        return true;
    }
}
