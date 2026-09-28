package com.code.pal.project.repository;

import com.code.pal.project.domain.RequirementQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequirementQuestionRepository extends JpaRepository<RequirementQuestion, UUID> {
    List<RequirementQuestion> findBySessionIdOrderBySequenceAsc(UUID sessionId);
    Optional<RequirementQuestion> findByIdAndSessionId(UUID id, UUID sessionId);
}
