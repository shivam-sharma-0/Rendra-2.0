package com.code.pal.workspace.repository;

import com.code.pal.workspace.domain.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, UUID> {

    /** Membership probe on the unique {@code (workspace_id, user_id)} index. */
    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(UUID workspaceId, Long userId);

    /**
     * Membership that is only valid while the workspace is active — the real access gate.
     * Joining {@code Workspace} applies its {@code @SQLRestriction}, so a soft-deleted workspace
     * yields no membership (access revoked) while the member rows survive for restore.
     */
    @Query("""
            select m from WorkspaceMember m, Workspace w
            where m.workspaceId = w.id and w.id = :workspaceId and m.userId = :userId
            """)
    Optional<WorkspaceMember> findActiveMembership(@Param("workspaceId") UUID workspaceId, @Param("userId") Long userId);

    /** All of a user's memberships — drives the "my workspaces" listing (keyed on the user_id index). */
    List<WorkspaceMember> findByUserId(Long userId);

    /** All members of a workspace — drives the member list / settings page. */
    List<WorkspaceMember> findByWorkspaceId(UUID workspaceId);
}
