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
    private final Map<String, Integer> patchMisses = new LinkedHashMap<>();
    private final java.util.Set<String> patchReadRequired = new java.util.HashSet<>();
    private int patchMissLimit = 3;

    public synchronized void patchMissLimit(int limit) { patchMissLimit = Math.max(1, Math.min(5, limit)); }
    public synchronized void patchFailed(String path) {
        path = normalize(path);
        patchMisses.merge(path, 1, Integer::sum);
        patchReadRequired.add(path);
        reads.remove(path); // Next read must return a body even if the version is unchanged.
    }
    public synchronized boolean patchNeedsRead(String path) { return patchReadRequired.contains(normalize(path)); }
    public synchronized boolean patchLimitReached() { return patchMisses.values().stream().anyMatch(count -> count >= patchMissLimit); }
    public synchronized java.util.List<String> patchBlockedPaths() { return patchMisses.entrySet().stream().filter(e -> e.getValue() >= patchMissLimit).map(Map.Entry::getKey).toList(); }
    public synchronized void patchSucceeded(String path) { patchMisses.remove(normalize(path)); patchReadRequired.remove(normalize(path)); }
    /** File read requests so far in the run; a repeat is only deduplicated while the read it repeats is recent. */
    private int readCount;
    /** Beyond this many later reads, a model may have lost the content in a long conversation: send it again. */
    static final int RECENT_READS = 6;
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
        readCount++;   // every request counts, so asking for the same file again and again gets its content back
        Fingerprint previous = reads.get(normalize(path));
        return previous != null && previous.size() == size && previous.version().equals(version)
                && readCount - previous.readIndex() < RECENT_READS;
    }
    public synchronized void forgetRead(String path){reads.remove(normalize(path));}

    public synchronized void recordRead(String path, long size, String version, int approximateChars) {
        reads.put(normalize(path), new Fingerprint(size, version, readCount));
        patchReadRequired.remove(normalize(path));
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
        return java.nio.file.Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    private record Fingerprint(long size, String version, int readIndex) {
    }
}
