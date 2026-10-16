package com.code.pal.project.supabase;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The one Supabase instance the server operator runs (local development stack or a self-hosted
 * deployment), configured in {@code app.supabase.self-hosted.*}. Projects connect to it by its URL; it has
 * no Management API. SQL is disabled unless the operator explicitly enables the single-tenant development path.
 * The URL and the database come from server configuration only, never from a user, so connecting cannot
 * make the backend reach an arbitrary host.
 */
@Component
@Slf4j
public class SelfHostedSupabase {
    private static final int QUERY_TIMEOUT_SECONDS = 90;

    private final String url;
    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;
    private final boolean operatorSqlEnabled;

    /** Direct construction remains default-deny, including fixtures that previously supplied only credentials. */
    public SelfHostedSupabase(String url, String databaseUrl, String databaseUser, String databasePassword) {
        this(url, databaseUrl, databaseUser, databasePassword, false);
    }

    @Autowired
    public SelfHostedSupabase(@Value("${app.supabase.self-hosted.url:}") String url,
                              @Value("${app.supabase.self-hosted.db-url:}") String databaseUrl,
                              @Value("${app.supabase.self-hosted.db-user:postgres}") String databaseUser,
                              @Value("${app.supabase.self-hosted.db-password:}") String databasePassword,
                              @Value("${codepal.supabase.self-hosted.operator-sql-enabled:false}") boolean operatorSqlEnabled) {
        this.url = url == null ? "" : url.trim().replaceAll("/+$", "");
        this.databaseUrl = databaseUrl == null ? "" : databaseUrl.trim();
        this.databaseUser = databaseUser;
        this.databasePassword = databasePassword;
        this.operatorSqlEnabled = operatorSqlEnabled;
        if (operatorSqlEnabled) {
            log.warn("supabase_operator_sql_enabled: privileged SQL is enabled for single-tenant development only; never use on a shared/public server");
        }
    }

    /** Whether {@code projectUrl} (normalized, no trailing slash) is this server's instance. */
    public boolean matches(String projectUrl) {
        return !url.isEmpty() && url.equalsIgnoreCase(projectUrl);
    }

    public boolean migrationsAvailable() {
        return operatorSqlEnabled && !databaseUrl.isEmpty();
    }

    public boolean operatorSqlEnabled() {
        return operatorSqlEnabled;
    }

    public void requireOperatorSqlEnabled() {
        if (!operatorSqlEnabled) {
            throw new SelfHostedSqlDisabledException();
        }
    }

    /**
     * Uses an operator database transaction and requests PostgREST schema refresh for a local migration.
     * This development-only path does not confine SQL statements or provide tenant isolation.
     *
     * @throws SelfHostedSqlDisabledException when operator SQL is disabled
     * @throws SupabaseException with a sanitized SQL state when the operator database fails
     */
    public void runSql(String sql) {
        requireOperatorSqlEnabled();
        if (!migrationsAvailable()) {
            throw new SupabaseException("This server has no database configured for its Supabase instance");
        }
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
            String state = failure.getSQLState();
            if (state == null || !state.matches("[A-Z0-9]{5}")) {
                state = "unknown";
            }
            // Driver messages/cause chains may contain the URL, password or SQL; do not send them to a client or log.
            throw new SupabaseException("Self-hosted SQL failed (SQLSTATE " + state + "). Check the migration and operator database configuration.");
        }
    }
}
