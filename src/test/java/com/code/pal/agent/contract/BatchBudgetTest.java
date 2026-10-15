package com.code.pal.agent.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchBudgetTest {
    private final BatchBudget budget = new BatchBudget(new ObjectMapper());
    private final BatchBudget.Limits limits = new BatchBudget.Limits(32_000, 6_000, 32_000, 128, true);

    @Test
    void reasoningAllowanceIsInsideTheReducedOutputCap() {
        assertEquals(1_200, budget.estimate(List.of(source("index.html", "Hi")), "{}", limits).reasoningTokens());
        var small = new BatchBudget.Limits(32_000, 2_000, 32_000, 0, true);
        assertEquals(1_024, budget.estimate(List.of(source("index.html", "Hi")), "{}", small).reasoningTokens());
        assertFalse(budget.estimate(List.of(source("index.html", "Hi")), "{}",
                new BatchBudget.Limits(32_000, 1_024, 32_000, 0, true)).fits());
    }

    @Test
    void exactSerializedWrapperAndEscapingAllowanceAreCounted() {
        var mapper = new ObjectMapper();
        var source = source("src/a\"b.ts", "\"\\\n");
        var estimate = budget.estimate(List.of(source), "{}", limits);
        long envelope = mapper.writeValueAsBytes(Map.of("files",
                List.of(Map.of("path", source.path(), "expectedVersion", source.expectedVersion(), "content", "")))).length;
        assertEquals(envelope, estimate.envelopeBytes());
        assertEquals(2L * "\"\\\n".getBytes(StandardCharsets.UTF_8).length, estimate.escapedSourceBytes());
        assertEquals((int) Math.ceil(1.15 * (envelope + estimate.escapedSourceBytes()) / 3.5), estimate.visibleTokens());
    }

    @Test
    void unicodeSourceAndInputUseUtf8BytesRatherThanCharacterCounts() {
        var ascii = budget.estimate(List.of(source("a.ts", "a".repeat(1_000))), "a".repeat(500), limits);
        var unicode = budget.estimate(List.of(source("a.ts", "界".repeat(1_000))), "界".repeat(500), limits);
        assertEquals(ascii.escapedSourceBytes() * 3, unicode.escapedSourceBytes());
        assertTrue(unicode.visibleTokens() > ascii.visibleTokens());
        assertTrue(unicode.inputTokens() > ascii.inputTokens());
    }

    @Test
    void sourceFileAndCharacterBoundariesAreInclusive() {
        var six = new ArrayList<BatchBudget.Source>();
        for (int index = 0; index < 6; index++) {
            six.add(source("file" + index + ".ts", "x".repeat(1_000)));
        }
        assertTrue(budget.estimate(six, "{}", limits).fits());
        six.add(source("seventh.ts", ""));
        assertFalse(budget.estimate(six, "{}", limits).fits());
        assertFalse(budget.estimate(List.of(source("app.ts", "x".repeat(6_001))), "{}", limits).fits());
    }

    @Test
    void inputAndOutputCapsAndUnverifiedReasoningFailClosed() {
        var files = List.of(source("index.html", "Hello"));
        var sized = new BatchBudget.Limits(10, 6_000, 32_000, 0, true);
        assertTrue(budget.estimate(files, "x".repeat(10), sized).fits());
        assertFalse(budget.estimate(files, "x".repeat(11), sized).fits());
        var unknown = new BatchBudget.Limits(32_000, 6_000, 32_000, 0, false);
        assertFalse(budget.estimate(files, "{}", unknown).fits());
        var estimate = budget.estimate(files, "{}", limits);
        int exactWindow = estimate.inputTokens() + estimate.outputCap();
        assertTrue(budget.estimate(files, "{}", new BatchBudget.Limits(32_000, 6_000, exactWindow, 128, true)).fits());
        assertFalse(budget.estimate(files, "{}", new BatchBudget.Limits(32_000, 6_000, exactWindow - 1, 128, true)).fits());
    }

    @Test
    void bytesOrWrappersCannotPushVisibleOutputBeyondTheAllowance() {
        var fits = budget.estimate(List.of(source("a.ts", "x".repeat(6_000))), "{}", limits);
        var unicode = budget.estimate(List.of(source("a.ts", "界".repeat(6_000))), "{}", limits);
        assertTrue(fits.fits());
        assertFalse(unicode.fits());
        assertTrue(unicode.visibleTokens() + unicode.reasoningTokens() > unicode.outputCap());
    }

    private BatchBudget.Source source(String path, String text) {
        return new BatchBudget.Source(path, "absent", text.length(), text.getBytes(StandardCharsets.UTF_8).length);
    }
}
