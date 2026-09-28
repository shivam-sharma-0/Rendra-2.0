package com.code.pal.project.domain;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "requirement_questions", indexes = @Index(name = "ix_requirement_questions_session_seq", columnList = "session_id, sequence_no"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class RequirementQuestion {
    @Id @GeneratedValue(strategy = GenerationType.UUID) UUID id;
    @Column(name = "session_id", nullable = false) UUID sessionId;
    @Column(name = "sequence_no", nullable = false) int sequence;
    @Column(nullable = false, length = 80) String questionKey;
    @Column(nullable = false, columnDefinition = "text") String prompt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) RequirementQuestionType type;
    @Column(nullable = false) boolean required;
    @Column(name = "options_json", nullable = false, columnDefinition = "text") String optionsJson;
    @Column(name = "recommended_option", length = 80) String recommendedOption;
    @Column(name = "selected_option", length = 80) String selectedOption;
    @Column(name = "other_text", columnDefinition = "text") String otherText;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) Instant updatedAt;
}
