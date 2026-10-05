package com.code.pal.project.supabase;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The one Supabase instance the server operator runs (local development stack or a self-hosted
 * deployment), configured in {@code app.supabase.self-hosted.*}. Projects connect to it by its URL; it has
 * no Management API, so approved migrations run over its database connection, each in one transaction.
 * The URL and the database come from server configuration only, never from a user, so connecting cannot
 * make the backend reach an arbitrary host.
 */
@Component
public class SelfHostedSupabase {
    private static final int QUERY_TIMEOUT_SECONDS = 90;

    private final String url;
    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;

    public SelfHostedSupabase(@Value("${app.supabase.self-hosted.url:}") String url,
                              @Value("${app.supabase.self-hosted.db-url:}") String databaseUrl,
                              @Value("${app.supabase.self-hosted.db-user:postgres}") String databaseUser,
                              @Value("${app.supabase.self-hosted.db-password:}") String databasePassword) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.databaseUrl = databaseUrl == null ? "" : databaseUrl.trim();
        this.databaseUser = databaseUser;
        this.databasePassword = databasePassword;
    }

    /** Whether {@code projectUrl} (normalized, no trailing slash) is this server's instance. */
    public boolean matches(String projectUrl) {
        return !url.isEmpty() && url.equalsIgnoreCase(projectUrl);
    }

    public boolean migrationsAvailable() {
        return !databaseUrl.isEmpty();
    }

    /**
     * Runs one migration in a transaction (all of it or nothing), then asks PostgREST to reload its schema
     * cache so new tables are served at once.
     *
     * @throws SupabaseException with the database's message when it fails
     */
    public void runSql(String sql) {
        if (!migrationsAvailable()) throw new SupabaseException("This server has no database configured for its Supabase instance");
        try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.execute(sql);
                statement.execute("NOTIFY pgrst, 'reload schema'");
                connection.commit();
            } catch (SQLException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new SupabaseException(failure.getMessage());
        }
    }
}
