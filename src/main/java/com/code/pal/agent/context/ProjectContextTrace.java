package com.code.pal.agent.context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe per-run trace updated by context creation and progressive discovery tools. */
public final class ProjectContextTrace {
    private final Map<String, ContextDiagnostics.SelectedPath> selected = new LinkedHashMap<>();
    private final List<ContextDiagnostics.OmittedPath> omitted = new ArrayList<>();
    private final Map<String, Fingerprint> reads = new LinkedHashMap<>();
    private int omittedConversationMessages;
    private int initialChars;
    private int budgetChars;

    public synchronized void selected(String path, ContextSelectionReason reason, int approximateChars) {
        if (path == null || path.isBlank()) return;
        selected.putIfAbsent(normalize(path) + "|" + reason.name(), new ContextDiagnostics.SelectedPath(
                normalize(path), reason, Math.max(0, approximateChars)));
    }

    public synchronized void omitted(String path, String reason) {
        if (path == null || path.isBlank()) return;
        omitted.add(new ContextDiagnostics.OmittedPath(normalize(path), reason));
    }

    public synchronized boolean wasReadUnchanged(String path, long size, String version) {
        return new Fingerprint(size, version).equals(reads.get(normalize(path)));
    }

    public synchronized void recordRead(String path, long size, String version, int approximateChars) {
        reads.put(normalize(path), new Fingerprint(size, version));
        selected(path, ContextSelectionReason.FILE_READ, approximateChars);
    }

    public synchronized void initializeBudget(int approximateChars, int budgetChars, int omittedMessages) {
        this.initialChars = Math.max(0, approximateChars);
        this.budgetChars = Math.max(1, budgetChars);
        this.omittedConversationMessages = Math.max(0, omittedMessages);
    }

    public synchronized ContextDiagnostics diagnostics(int currentChars) {
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (ContextDiagnostics.OmittedPath item : omitted) reasons.merge(item.reason(), 1, Integer::sum);
        int chars = Math.max(initialChars, currentChars);
        return new ContextDiagnostics(List.copyOf(selected.values()), List.copyOf(omitted), chars,
                Math.max(1, (chars + 3) / 4), budgetChars, omitted.size(), omittedConversationMessages,
                Map.copyOf(reasons));
    }

    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private record Fingerprint(long size, String version) {
    }
}
