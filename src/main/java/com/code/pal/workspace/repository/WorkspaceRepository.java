package com.code.pal.workspace.repository;

import com.code.pal.workspace.domain.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /** The caller's private workspace — used to find-or-create it lazily on first use. */
    Optional<Workspace> findByOwnerIdAndPersonalTrue(Long ownerId);
}
