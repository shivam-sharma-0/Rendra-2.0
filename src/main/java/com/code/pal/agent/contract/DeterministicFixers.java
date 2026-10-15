package com.code.pal.agent.contract;

import com.code.pal.agent.starter.Starter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Exact reviewed starter transformations only. No shell text, dependency upgrades or behavioral guesses. */
public final class DeterministicFixers {
    public record Fix(Map<String, String> replacements, boolean refreshLockfiles, String signature) {
        public Fix {
            replacements = Map.copyOf(replacements);
        }
    }

    private final ObjectMapper mapper;

    public DeterministicFixers(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Fix plan(BuildContract contract, Map<String, String> snapshot, Set<String> defects) {
        var replacements = new LinkedHashMap<String, String>();
        boolean refresh = false;
        if (contract.schemaVersion() == 2 && contract.stack() == BuildContract.Stack.VITE) {
            var trusted = Starter.VITE_REACT.files("App");
            if (defects.contains("PREVIEW_ENTRYPOINT_MISSING") && !snapshot.containsKey("index.html")
                    && snapshot.containsKey("src/main.tsx")) {
                replacements.put("index.html", trusted.get("index.html"));
            }
            String source = snapshot.get("package.json");
            if (source != null && source.length() <= 256_000) {
                var manifest = mapper.readTree(source);
                if (manifest instanceof ObjectNode object) {
                    var pinned = mapper.readTree(trusted.get("package.json"));
                    boolean changed = false;
                    if (defects.contains("DEPENDENCY_VERSION_UNAVAILABLE")) {
                        for (String group : Set.of("dependencies", "devDependencies")) {
                            if (object.path(group) instanceof ObjectNode dependencies) {
                                for (var entry : pinned.path(group).properties()) {
                                    String version = entry.getValue().asString();
                                    // An exact major.minor alias resolves to the same reviewed major.minor patch.
                                    String alias = version.substring(0, version.lastIndexOf('.'));
                                    if (alias.equals(dependencies.path(entry.getKey()).asString())) {
                                        dependencies.put(entry.getKey(), version);
                                        changed = true;
                                    }
                                }
                            }
                        }
                    }
                    if (changed) {
                        replacements.put("package.json", mapper.writeValueAsString(object));
                    }
                    boolean npm = !snapshot.containsKey("pnpm-lock.yaml") && !snapshot.containsKey("yarn.lock")
                            && (!object.has("packageManager") || object.path("packageManager").asString().startsWith("npm@"));
                    refresh = npm && (changed || defects.contains("LOCKFILE_MISMATCH"));
                }
            }
        }
        String signature = ContractStore.hash(mapper.writeValueAsString(Map.of("contract", contract.id(), "revision", ContractScaffold.revision(),
                "input", new TreeMap<>(snapshot), "defects", new TreeSet<>(defects))));
        return new Fix(replacements, refresh, signature);
    }

    public static boolean canAttempt(Fix fix, Set<String> attemptedSignatures, int attemptedCount) {
        return attemptedCount < 3 && !attemptedSignatures.contains(fix.signature())
                && (!fix.replacements().isEmpty() || fix.refreshLockfiles());
    }
}
