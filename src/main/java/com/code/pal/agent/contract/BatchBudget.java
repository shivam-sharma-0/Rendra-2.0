package com.code.pal.agent.contract;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure, conservative fast-path estimate; completion and actual call admission still need validation. */
public final class BatchBudget {
    public static final int MAX_SOURCE_FILES = 6;
    public static final int MAX_SOURCE_CHARACTERS = 6_000;
    private static final int NOMINAL_OUTPUT_CAP = 6_000;
    private final ObjectMapper mapper;

    public BatchBudget(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** Projected UTF-8 bytes account for source language, separately from the character allowance. */
    public record Source(String path, String expectedVersion, int characters, long utf8Bytes) {
        public Source {
            if (path == null || path.isBlank() || expectedVersion == null || expectedVersion.isBlank()
                    || characters < 0 || utf8Bytes < characters || utf8Bytes > 4L * characters) {
                throw new IllegalArgumentException("Invalid source projection");
            }
        }
    }

    /** inputCap counts serialized UTF-8 bytes; context/output caps count tokens. */
    public record Limits(int inputCap, int outputCap, int modelContextTokens, int providerOverheadTokens,
            boolean verifiedOutputPolicy) {
        public Limits {
            if (inputCap < 1 || outputCap < 1 || modelContextTokens < 1 || providerOverheadTokens < 0) {
                throw new IllegalArgumentException("Invalid batch limits");
            }
            outputCap = Math.min(NOMINAL_OUTPUT_CAP, outputCap);
        }
    }

    public record Estimate(long sourceCharacters, long escapedSourceBytes, long envelopeBytes, int visibleTokens,
            int reasoningTokens, int inputTokens, int outputCap, boolean fits) {}

    public Estimate estimate(List<Source> files, String serializedInput, Limits limits) {
        files = List.copyOf(files);
        Objects.requireNonNull(serializedInput);
        Objects.requireNonNull(limits);
        long characters = files.stream().mapToLong(Source::characters).sum();
        long sourceBytes = files.stream().mapToLong(Source::utf8Bytes).sum() * 2;
        var envelope = Map.of("files", files.stream().map(file -> Map.of("path", file.path(),
                "expectedVersion", file.expectedVersion(), "content", "")).toList());
        long envelopeBytes = mapper.writeValueAsBytes(envelope).length;
        int visible = tokens(sourceBytes + envelopeBytes);
        int reasoning = Math.max(1_024, (int) Math.ceil(0.20 * limits.outputCap()));
        long inputBytes = serializedInput.getBytes(StandardCharsets.UTF_8).length;
        int inputTokens = (int) Math.min(Integer.MAX_VALUE, (long) tokens(inputBytes) + limits.providerOverheadTokens());
        boolean fits = !files.isEmpty() && files.size() <= MAX_SOURCE_FILES && characters <= MAX_SOURCE_CHARACTERS
                && inputBytes <= limits.inputCap() && limits.verifiedOutputPolicy()
                && (long) visible + reasoning <= limits.outputCap()
                && (long) inputTokens + limits.outputCap() <= limits.modelContextTokens();
        return new Estimate(characters, sourceBytes, envelopeBytes, visible, reasoning, inputTokens, limits.outputCap(), fits);
    }

    private static int tokens(long bytes) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceil(1.15 * bytes / 3.5));
    }
}
