package com.code.pal.project.brief;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectBriefFileTest {

    @Test
    void theFirstBriefHoldsTheProjectItsRequirementsAndRoomForDecisions() {
        String brief = ProjectBriefFile.initial("Rock paper scissors", "Build a rock paper scissors game.",
                List.of(new ProjectBriefFile.Requirement("Who is it for?", "Kids"),
                        new ProjectBriefFile.Requirement("Keep\nscore?", null)),
                LocalDate.of(2026, 10, 10));
        assertTrue(brief.startsWith("# Rock paper scissors\n"), brief);
        assertTrue(brief.contains("Rendra reads it before every change"));
        assertTrue(brief.contains("## What it is\n\nBuild a rock paper scissors game."));
        assertTrue(brief.contains("## Requirements (approved 2026-10-10)"));
        assertTrue(brief.contains("- Who is it for?: Kids\n"));
        assertTrue(brief.contains("- Keep score?: Not specified\n"), "answers and questions stay on one line");
        assertTrue(brief.contains("## Decisions"));
    }

    @Test
    void aLongPromptIsClipped() {
        String brief = ProjectBriefFile.initial(null, "x".repeat(5_000), List.of(), LocalDate.of(2026, 10, 10));
        assertTrue(brief.startsWith("# Project\n"));
        assertTrue(brief.length() < 1_500, "length " + brief.length());
    }
}
