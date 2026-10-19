package com.code.pal.agent.workspace;

import com.code.pal.agent.contract.ContractStore;

import java.nio.file.Path;
import java.util.Locale;

/** Stable resource ownership for one checkout/workspace, including Windows path case normalization. */
final class WorkspaceNamespace {
    private WorkspaceNamespace() {}

    static String of(Path root) {
        String base = root.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) base = base.toLowerCase(Locale.ROOT);
        return ContractStore.hash(base).substring(0, 16);
    }
}
