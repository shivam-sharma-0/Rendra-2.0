package com.code.pal.agent.preview;

import com.code.pal.identity.security.AuthSessions;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.security.AuthPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;

/** Short-lived links bind a current user/session/project and, for a draft, its exact run. */
@Service
public final class PreviewGrants {
    public record Grant(UUID id, UUID projectId, Long userId, UUID sessionId, UUID draftRunId, Instant expiresAt) {
        public String key() {
            return id.toString().replace("-", "");
        }
    }

    private final JdbcTemplate jdbc;
    private final AuthSessions sessions;
    private final ProjectService projects;
    private final long ttl;

    public PreviewGrants(JdbcTemplate jdbc, AuthSessions sessions, ProjectService projects,
            @Value("${jwt.preview-expiry:1800000}") long ttl) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.projects = projects;
        this.ttl = Math.min(1800000, Math.max(1000, ttl));
    }

    public Grant issue(AuthPrincipal actor, UUID project, UUID run) {
        projects.requireAccessible(actor.userId(), project);
        if (!sessions.active(actor.sessionId(), actor.userId()))
            throw missing();
        if (run != null && jdbc.queryForObject(
                "select count(*) from agent_runs where id=? and project_id=? and status in ('RUNNING','COMMITTING')",
                Integer.class, run, project) != 1)
            throw missing();
        var existing = jdbc.query(
                "select * from preview_grants where project_id=? and user_id=? and session_id=? and draft_run_id is not distinct from ? and expires_at>clock_timestamp()+interval '1 minute' order by expires_at desc limit 1",
                PreviewGrants::row, project, actor.userId(), actor.sessionId(), run);
        if (!existing.isEmpty())
            return existing.getFirst();
        var grant = new Grant(UUID.randomUUID(), project, actor.userId(), actor.sessionId(), run,
                Instant.now().plusMillis(ttl).truncatedTo(ChronoUnit.MILLIS));
        jdbc.update(
                "insert into preview_grants(id,project_id,user_id,session_id,draft_run_id,expires_at) values (?,?,?,?,?,?)",
                grant.id(), project, actor.userId(), actor.sessionId(), run, Timestamp.from(grant.expiresAt()));
        jdbc.update(
                "delete from preview_grants where id in (select id from preview_grants where project_id=? and user_id=? and session_id=? order by expires_at desc,id desc offset 8)",
                project, actor.userId(), actor.sessionId());
        return grant;
    }

    private static Grant row(ResultSet row, int index) throws SQLException {
        return new Grant(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                row.getLong("user_id"), row.getObject("session_id", UUID.class),
                row.getObject("draft_run_id", UUID.class), row.getTimestamp("expires_at").toInstant());
    }

    public Grant resolve(String key) {
        if (key == null || !key.matches("[a-f0-9]{32}"))
            throw missing();
        return resolve(new UUID(Long.parseUnsignedLong(key.substring(0, 16), 16),
                Long.parseUnsignedLong(key.substring(16), 16)));
    }

    public Grant resolve(UUID id) {
        if (id == null)
            throw missing();
        var rows = jdbc.query("select * from preview_grants where id=? and expires_at>clock_timestamp()",
                PreviewGrants::row, id);
        if (rows.isEmpty())
            throw missing();
        var grant = rows.getFirst();
        if (!sessions.active(grant.sessionId(), grant.userId()))
            throw missing();
        projects.requireAccessible(grant.userId(), grant.projectId());
        return grant;
    }

    public void revoke(AuthPrincipal actor, UUID project) {
        projects.requireAccessible(actor.userId(), project);
        if (!sessions.active(actor.sessionId(), actor.userId()))
            throw missing();
        jdbc.update("delete from preview_grants where project_id=? and user_id=? and session_id=?", project,
                actor.userId(), actor.sessionId());
    }

    @Scheduled(fixedDelayString = "${app.preview.grant-cleanup-ms:3600000}")
    public void prune() {
        jdbc.update("delete from preview_grants where expires_at<clock_timestamp()");
    }

    private static RuntimeException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview not found");
    }
}
