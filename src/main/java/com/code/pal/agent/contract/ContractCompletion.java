package com.code.pal.agent.contract;

import java.util.TreeSet;
import java.util.function.Function;

/** Intermediate scaffold stubs are useful for compilation, but never certify a completed v2 build. */
public final class ContractCompletion {
    private ContractCompletion() {
    }

    public static void require(BuildContract contract, Function<String, String> read) {
        if (contract.schemaVersion() != 2) {
            return;
        }
        var entrypoints = new TreeSet<String>();
        contract.routes().forEach(route -> entrypoints.add(route.componentPath()));
        entrypoints.add(contract.stack() == BuildContract.Stack.STATIC ? "index.html" : "src/App.tsx");
        for (String path : entrypoints) {
            String content = read.apply(path);
            if (content == null || content.isBlank()
                    || content.matches("(?s).*export default function Pending(?:App|Route)\\(\\) \\{ return null }.*")) {
                throw new IllegalArgumentException("Required application entrypoint is missing or still a staging placeholder: " + path);
            }
        }
    }
}
