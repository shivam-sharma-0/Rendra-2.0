package com.code.pal.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** The project's durable soft-delete is the job. Folder loss never loses pending database cleanup. */
@Component
public final class DeletedProjectCleanupJobs {
    private final JdbcTemplate jdbc;

    public DeletedProjectCleanupJobs(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> pending() {
        return jdbc.query(
                "select id from projects where deleted_at is not null and cleanup_completed_at is null order by coalesce(cleanup_attempted_at,deleted_at),id limit 100",
                (row, n) -> row.getObject(1, UUID.class));
    }

    public void attempted(UUID id) {
        jdbc.update(
                "update projects set cleanup_attempted_at=clock_timestamp() where id=? and deleted_at is not null and cleanup_completed_at is null",
                id);
    }

    public void completed(UUID id) {
        jdbc.update(
                "update projects set cleanup_completed_at=clock_timestamp() where id=? and deleted_at is not null and cleanup_completed_at is null",
                id);
    }
}
