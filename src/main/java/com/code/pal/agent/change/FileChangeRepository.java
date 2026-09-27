package com.code.pal.agent.change;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileChangeRepository extends JpaRepository<FileChange, UUID> {
    List<FileChange> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
