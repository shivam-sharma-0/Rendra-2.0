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
                          boolean complete, Instant approvedAt, long revision, boolean processing, String artifactStatus, String reviewStatus) {
        public Session(UUID id, UUID projectId, RequirementStatus status, List<Question> questions, boolean complete, Instant approvedAt) {
            this(id, projectId, status, questions, complete, approvedAt, 0, false, "NONE", "NONE");
        }
    }
    public record ApprovalRequest(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long revision) {}
    public record AnswerRequest(String selectedOption, String otherText,
                                @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.PositiveOrZero Long revision) {
        public AnswerRequest(String selectedOption, String otherText) { this(selectedOption, otherText, null); }
    }
}
