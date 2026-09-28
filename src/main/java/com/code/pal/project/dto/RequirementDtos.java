package com.code.pal.project.dto;

import com.code.pal.project.domain.RequirementQuestionType;
import com.code.pal.project.domain.RequirementStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RequirementDtos {
    private RequirementDtos() {}

    public record Option(String id, String label, String description, boolean recommended) {}
    public record Question(UUID id, int sequence, String key, String prompt, RequirementQuestionType type,
                           boolean required, List<Option> options, String recommendedOption,
                           String selectedOption, String otherText) {}
    public record Session(UUID id, UUID projectId, RequirementStatus status, List<Question> questions,
                          boolean complete, Instant approvedAt) {}
    public record AnswerRequest(String selectedOption, String otherText) {}
}
