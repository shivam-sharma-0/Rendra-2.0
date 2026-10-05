package com.code.pal.project.supabase;

import com.code.pal.agent.preview.BuildVariables;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.project.domain.ProjectEnvironmentVariable;
import com.code.pal.project.exception.WorkflowStageException;
import com.code.pal.project.repository.ProjectEnvironmentVariableRepository;
import com.code.pal.project.service.ProjectService;
import com.code.pal.shared.config.DataCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects a generated project to the user's own Supabase project and applies the database migrations
 * the agent writes ({@code supabase/migrations/*.sql}) once the user approves them.
 *
 * <p>The URL and anon key are public: they become the project's {@code VITE_SUPABASE_URL} and
 * {@code VITE_SUPABASE_ANON_KEY} build variables. The optional personal access token is what lets the
 * backend run SQL; it is stored encrypted and never returned or shown to the agent. The agent never
 * applies SQL itself: a migration runs only when the user approves it, in name order, each at most once;
 * a file changed after it was applied is reported, not run again. Calls to Supabase are made outside
 * database transactions.
 */
@Slf4j
@Service
public class SupabaseService {
    public static final String URL_VARIABLE = "VITE_SUPABASE_URL";
    public static final String KEY_VARIABLE = "VITE_SUPABASE_ANON_KEY";
    static final String MIGRATIONS = "supabase/migrations";
    private static final int MAX_MIGRATION_BYTES = 200_000;

    private final ProjectService projects;
    private final SupabaseConnectionRepository connections;
    private final SupabaseMigrationRepository migrations;
    private final ProjectEnvironmentVariableRepository variables;
    private final SupabaseClient supabase;
    private final SelfHostedSupabase selfHosted;
    private final DataCipher cipher;
    private final ProjectFiles files;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Pattern projectUrl;
    private final Map<UUID, ReentrantLock> applying = new ConcurrentHashMap<>();

    public SupabaseService(ProjectService projects, SupabaseConnectionRepository connections, SupabaseMigrationRepository migrations,
                           ProjectEnvironmentVariableRepository variables, SupabaseClient supabase, SelfHostedSupabase selfHosted, DataCipher cipher,
                           ProjectFiles files, ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                           @Value("${app.supabase.project-url-pattern:^https://([a-z0-9]{20})\\.supabase\\.co$}") String projectUrlPattern) {
        this.projects = projects;
        this.connections = connections;
        this.migrations = migrations;
        this.variables = variables;
        this.supabase = supabase;
        this.selfHosted = selfHosted;
        this.cipher = cipher;
        this.files = files;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
        this.projectUrl = Pattern.compile(projectUrlPattern);
    }

    public record Status(boolean connected, String url, String projectRef, boolean migrationsEnabled) {}

    public record Migration(String name, String status, String sql) {}

    public record ApplyResult(List<String> applied, String failed, String error, List<String> remaining) {}

    public Status status(Long userId, UUID projectId) {
        return tx.execute(state -> {
            projects.requireCanEdit(userId, projectId);
            return connections.findById(projectId)
                    .map(c -> new Status(true, c.getUrl(), c.getProjectRef(), migrationsEnabled(c)))
                    .orElse(new Status(false, null, null, false));
        });
    }

    /** Checks the credentials with Supabase, then stores them. */
    public Status connect(Long userId, UUID projectId, String url, String anonKey, String accessToken) {
        tx.executeWithoutResult(state -> projects.requireManageable(userId, projectId));
        String cleanUrl = url == null ? "" : url.trim().replaceAll("/+$", "");
        boolean onThisServer = selfHosted.matches(cleanUrl);
        Matcher match = projectUrl.matcher(cleanUrl);
        if (!onThisServer && !match.matches()) {
            throw new SupabaseException("Use the project URL from Supabase settings, like https://<project>.supabase.co");
        }
        String projectRef = onThisServer ? "self-hosted" : match.group(1);
        if (anonKey == null || anonKey.isBlank() || anonKey.length() > 2_000) throw new SupabaseException("The anon (public) key is required");
        // The self-hosted instance applies migrations over the server's own database connection: no token.
        String token = onThisServer || accessToken == null || accessToken.isBlank() ? null : accessToken.trim();
        if (token != null && !cipher.available()) {
            throw new WorkflowStageException("This server cannot store access tokens yet (APP_DATA_KEY is not configured)");
        }
        supabase.verifyAnonKey(cleanUrl, anonKey.trim());
        if (token != null) supabase.verifyAccessToken(projectRef, token);
        String tokenCipher = token == null ? null : cipher.encrypt(token);

        tx.executeWithoutResult(state -> {
            SupabaseConnection connection = connections.findById(projectId).orElseGet(() -> SupabaseConnection.builder().projectId(projectId).build());
            if (connection.getProjectRef() != null && !connection.getProjectRef().equals(projectRef)) {
                migrations.deleteByProjectId(projectId);   // a different database: nothing is applied there yet
            }
            connection.setProjectRef(projectRef);
            connection.setUrl(cleanUrl);
            connection.setAnonKey(anonKey.trim());
            if (token != null) connection.setAccessTokenCipher(tokenCipher);
            connection.setMode(onThisServer ? SupabaseConnection.Mode.SELF_HOSTED : SupabaseConnection.Mode.CLOUD);
            if (onThisServer) connection.setAccessTokenCipher(null);
            connection.setConnectedBy(userId);
            connections.save(connection);
            setVariable(projectId, URL_VARIABLE, cleanUrl);
            setVariable(projectId, KEY_VARIABLE, anonKey.trim());
            events.publishEvent(new BuildVariables.Changed(projectId));
        });
        log.info("supabase_connected projectId={} ref={} selfHosted={}", projectId, projectRef, onThisServer);
        return status(userId, projectId);
    }

    public void disconnect(Long userId, UUID projectId) {
        tx.executeWithoutResult(state -> {
            projects.requireManageable(userId, projectId);
            connections.deleteById(projectId);
            migrations.deleteByProjectId(projectId);
            for (String name : List.of(URL_VARIABLE, KEY_VARIABLE)) variables.findByProjectIdAndName(projectId, name).ifPresent(variables::delete);
            events.publishEvent(new BuildVariables.Changed(projectId));
        });
    }

    /** Every migration file in the project, with whether it is applied, pending, or changed since it was applied. */
    public List<Migration> migrations(Long userId, UUID projectId) {
        Map<String, SupabaseMigration> applied = tx.execute(state -> {
            projects.requireCanEdit(userId, projectId);
            return appliedByName(projectId);
        });
        List<Migration> result = new ArrayList<>();
        for (String name : migrationFiles(projectId)) {
            String sql = read(projectId, name);
            SupabaseMigration record = applied.get(name);
            String status = record == null ? "PENDING" : record.getChecksum().equals(checksum(sql)) ? "APPLIED" : "CHANGED_AFTER_APPLY";
            result.add(new Migration(name, status, sql));
        }
        return result;
    }

    /** Runs the pending migrations in name order; stops at the first failure. One apply per project at a time. */
    public ApplyResult apply(Long userId, UUID projectId) {
        SupabaseConnection connection = tx.execute(state -> {
            projects.requireManageable(userId, projectId);
            return connections.findById(projectId).orElseThrow(() -> new WorkflowStageException("Connect Supabase first"));
        });
        if (!migrationsEnabled(connection)) {
            throw new WorkflowStageException(connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED
                    ? "This server has no database configured for its Supabase instance"
                    : "Add a Supabase access token to apply migrations from here");
        }
        java.util.function.Consumer<String> runner;
        if (connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED) {
            runner = selfHosted::runSql;
        } else {
            String token = cipher.decrypt(connection.getAccessTokenCipher());
            runner = sql -> supabase.runSql(connection.getProjectRef(), token, sql);
        }
        ReentrantLock lock = applying.computeIfAbsent(projectId, ignored -> new ReentrantLock());
        if (!lock.tryLock()) throw new WorkflowStageException("Migrations are already being applied for this project");
        try {
            Map<String, SupabaseMigration> applied = tx.execute(state -> appliedByName(projectId));
            List<String> pending = migrationFiles(projectId).stream().filter(name -> !applied.containsKey(name)).toList();
            List<String> done = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                String name = pending.get(i);
                String sql = read(projectId, name);
                try {
                    runner.accept(sql);
                } catch (SupabaseException failure) {
                    log.warn("supabase_migration_failed projectId={} migration={} reason={}", projectId, name, failure.getMessage());
                    return new ApplyResult(done, name, failure.getMessage(), pending.subList(i + 1, pending.size()));
                }
                tx.executeWithoutResult(state -> migrations.save(SupabaseMigration.builder().projectId(projectId).name(name)
                        .checksum(checksum(sql)).appliedBy(userId).appliedAt(Instant.now()).build()));
                done.add(name);
                log.info("supabase_migration_applied projectId={} migration={}", projectId, name);
            }
            return new ApplyResult(done, null, null, List.of());
        } finally {
            lock.unlock();
        }
    }

    private boolean migrationsEnabled(SupabaseConnection connection) {
        return connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED
                ? selfHosted.migrationsAvailable() : connection.getAccessTokenCipher() != null;
    }

    private Map<String, SupabaseMigration> appliedByName(UUID projectId) {
        Map<String, SupabaseMigration> result = new HashMap<>();
        migrations.findByProjectIdOrderByNameAsc(projectId).forEach(migration -> result.put(migration.getName(), migration));
        return result;
    }

    /** {@code supabase/migrations/*.sql} of the live project, by name (timestamp prefixes order them). */
    private List<String> migrationFiles(UUID projectId) {
        return files.list(projectId, MIGRATIONS, 1).stream()
                .filter(entry -> !entry.directory() && entry.path().endsWith(".sql") && entry.path().indexOf('/') < 0)
                .map(ProjectFiles.Entry::path).sorted().toList();
    }

    private String read(UUID projectId, String name) {
        return new String(files.readBytes(projectId, MIGRATIONS, name, MAX_MIGRATION_BYTES), StandardCharsets.UTF_8);
    }

    private void setVariable(UUID projectId, String name, String value) {
        ProjectEnvironmentVariable variable = variables.findByProjectIdAndName(projectId, name)
                .orElseGet(() -> ProjectEnvironmentVariable.builder().projectId(projectId).name(name).build());
        variable.setValue(value);
        variables.save(variable);
    }

    static String checksum(String sql) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
