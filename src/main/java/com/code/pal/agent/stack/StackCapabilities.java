package com.code.pal.agent.stack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a project's manifests declare. {@code error} explains why nothing could be read (for example a
 * static site has no manifest); the other fields are then empty.
 */
public record StackCapabilities(String stack, String packageManager, String declaredPackageManager,
                                Map<String, String> scripts, List<String> availableOperations, String error) {

    public static StackCapabilities unavailable(String stack, String reason) {
        return new StackCapabilities(stack, null, null, Map.of(), List.of(), reason);
    }

    static List<String> operations(boolean typeCheck, boolean build, boolean tests) {
        List<String> result = new ArrayList<>();
        if (typeCheck) result.add("runTypeCheck");
        if (build) result.add("runBuild");
        if (tests) result.add("runTests");
        return List.copyOf(result);
    }
}
