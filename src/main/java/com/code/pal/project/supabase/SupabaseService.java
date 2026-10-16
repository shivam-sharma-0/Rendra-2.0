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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
    private static final int MAX_MIGRATION_BYTES = SupabaseMigrationManifest.MAX_FILE_BYTES;

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
    private final SupabaseMigrationLedger ledger;

    public SupabaseService(ProjectService projects, SupabaseConnectionRepository connections, SupabaseMigrationRepository migrations,
                           ProjectEnvironmentVariableRepository variables, SupabaseClient supabase, SelfHostedSupabase selfHosted, DataCipher cipher,
                           ProjectFiles files, ApplicationEventPublisher events, PlatformTransactionManager transactionManager, SupabaseMigrationLedger ledger,
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
        this.ledger = Objects.requireNonNull(ledger);
        this.projectUrl = Pattern.compile(projectUrlPattern);
    }

    public record Status(boolean connected, String url, String projectRef, boolean migrationsEnabled) {}

    public record Migration(String name, String status, String sql) {}

    public record ApplyResult(List<String> applied, String failed, String error, List<String> remaining, UUID operationId, String state) {}

    public record MigrationReview(String expectedManifestHash, UUID connectionEpoch, List<Migration> migrations) {}

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
        if (onThisServer) {
            selfHosted.requireOperatorSqlEnabled();   // the operator's shared instance is not offered to tenants
        }
        Matcher match = projectUrl.matcher(cleanUrl);
        if (!onThisServer && !match.matches()) {
            throw new SupabaseException("Use the project URL from Supabase settings, like https://<project>.supabase.co");
        }
        String projectRef = onThisServer ? "self-hosted" : match.group(1);
        if (anonKey == null || anonKey.isBlank() || anonKey.length() > 2_000) throw new SupabaseException("The anon (public) key is required");
        SupabaseKeys.requirePublic(anonKey);
        // The self-hosted instance applies migrations over the server's own database connection: no token.
        String token = onThisServer || accessToken == null || accessToken.isBlank() ? null : accessToken.trim();
        if (token != null && !cipher.available()) {
            throw new WorkflowStageException("This server cannot store access tokens yet (APP_DATA_KEY is not configured)");
        }
        supabase.verifyAnonKey(cleanUrl, anonKey.trim());
        if (token != null) supabase.verifyAccessToken(projectRef, token);
        String tokenCipher = token == null ? null : cipher.encrypt(token);

        tx.executeWithoutResult(state -> {
            // Re-authorize and fence against an apply in flight before touching the connection or its history.
            projects.requireManageable(userId, projectId);
            ledger.guardConnectionChange(projectId);
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
            connections.saveAndFlush(connection);
            setVariable(projectId, URL_VARIABLE, cleanUrl);
            setVariable(projectId, KEY_VARIABLE, anonKey.trim());
            ledger.connectionChanged(projectId);   // approvals made against the old connection no longer match
            events.publishEvent(new BuildVariables.Changed(projectId));
        });
        log.info("supabase_connected projectId={} ref={} selfHosted={}", projectId, projectRef, onThisServer);
        return status(userId, projectId);
    }

    public void disconnect(Long userId, UUID projectId) {
        tx.executeWithoutResult(state -> {
            projects.requireManageable(userId, projectId);
            ledger.guardConnectionChange(projectId);
            connections.deleteById(projectId);
            migrations.deleteByProjectId(projectId);
            for (String name : List.of(URL_VARIABLE, KEY_VARIABLE)) variables.findByProjectIdAndName(projectId, name).ifPresent(variables::delete);
            connections.flush();
            ledger.connectionChanged(projectId);
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

    /** Legacy clients must review and submit an exact manifest; never fall back to the former local lock. */
    public ApplyResult apply(Long userId, UUID projectId) {
        requireApplyConnection(userId, projectId);
        throw new SupabaseMigrationException("MIGRATION_MANIFEST_REQUIRED");
    }

    /** Exact approval input; includes SQL for the authorized owner, never access tokens or ledger ciphertext. */
    public MigrationReview review(Long userId, UUID projectId) {
        SupabaseConnection connection = requireApplyConnection(userId, projectId);
        SupabaseMigrationManifest manifest = capture(projectId, connection);
        Map<String, SupabaseMigration> applied = tx.execute(state -> appliedByName(projectId));
        List<Migration> reviewed = manifest.sources().stream().map(source -> {
            SupabaseMigration record = applied.get(source.name());
            String status = record == null ? "PENDING" : record.getChecksum().equals(source.checksum()) ? "APPLIED" : "CHANGED_AFTER_APPLY";
            return new Migration(source.name(), status, source.sql());
        }).toList();
        return new MigrationReview(manifest.hash(), manifest.connectionEpoch(), reviewed);
    }

    /** All remote execution is from the encrypted, durably claimed ledger snapshot. No transaction spans HTTP. */
    public ApplyResult apply(Long userId, UUID projectId, String expectedManifestHash, String idempotencyKey) {
        SupabaseConnection connection = requireApplyConnection(userId, projectId);
        if (expectedManifestHash == null || !expectedManifestHash.matches("sha256:[0-9a-f]{64}")) {
            throw new SupabaseMigrationException("MIGRATION_MANIFEST_REQUIRED");
        }
        SupabaseMigrationManifest manifest = capture(projectId, connection);
        requireApprovedManifest(expectedManifestHash, manifest);
        String token = connection.getMode() == SupabaseConnection.Mode.CLOUD ? cipher.decrypt(connection.getAccessTokenCipher()) : null;
        SupabaseMigrationLedger.Outcome outcome = ledger.execute(userId, manifest, idempotencyKey, () -> {
            SupabaseConnection current = requireApplyConnection(userId, projectId);
            requireApprovedManifest(expectedManifestHash, capture(projectId, current));
        }, source -> {
            if (connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED) selfHosted.runSql(source.sql());
            else supabase.runSql(connection.getProjectRef(), token, source.sql());
        });
        return result(outcome);
    }

    /** Poll durable status after a response loss; current source files need not still match the old request. */
    public ApplyResult operation(Long userId, UUID projectId, UUID operationId) {
        tx.executeWithoutResult(state -> projects.requireManageable(userId, projectId));
        return result(ledger.outcome(userId, projectId, operationId));
    }

    public ApplyResult operationByKey(Long userId, UUID projectId, String idempotencyKey) {
        tx.executeWithoutResult(state -> projects.requireManageable(userId, projectId));
        return result(ledger.outcomeByKey(userId, projectId, idempotencyKey));
    }

    private static ApplyResult result(SupabaseMigrationLedger.Outcome outcome) {
        return new ApplyResult(outcome.applied(), outcome.failed(), outcome.code(), outcome.remaining(), outcome.operationId(), outcome.state());
    }

    private SupabaseConnection requireApplyConnection(Long userId, UUID projectId) {
        SupabaseConnection connection = tx.execute(state -> {
            projects.requireManageable(userId, projectId);
            return connections.findById(projectId).orElseThrow(() -> new WorkflowStageException("Connect Supabase first"));
        });
        if (connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED) {
            selfHosted.requireOperatorSqlEnabled();
            if (!selfHosted.matches(connection.getUrl())) throw new SupabaseMigrationException("MIGRATION_CONNECTION_CHANGED");
        }
        if (!migrationsEnabled(connection)) {
            throw new WorkflowStageException(connection.getMode() == SupabaseConnection.Mode.SELF_HOSTED
                    ? "This server has no database configured for its Supabase instance"
                    : "Add a Supabase access token to apply migrations from here");
        }
        return connection;
    }

    private SupabaseMigrationManifest capture(UUID projectId, SupabaseConnection connection) {
        List<String> names = migrationFiles(projectId);
        if (names.size() > SupabaseMigrationManifest.MAX_FILES) throw new SupabaseMigrationException("MIGRATION_MANIFEST_TOO_LARGE");
        List<SupabaseMigrationManifest.Source> sources = new ArrayList<>();
        int bytes = 0;
        for (String name : names) {
            var source = SupabaseMigrationManifest.Source.of(name, read(projectId, name));
            sources.add(source);
            bytes += source.byteCount();
            if (bytes > SupabaseMigrationManifest.MAX_TOTAL_BYTES) throw new SupabaseMigrationException("MIGRATION_MANIFEST_TOO_LARGE");
        }
        return SupabaseMigrationManifest.create(projectId,
                SupabaseMigrationLedger.targetKey(connection.getMode().name(), connection.getProjectRef(), connection.getUrl()),
                ledger.connectionEpoch(projectId), sources);
    }

    private static void requireApprovedManifest(String expected, SupabaseMigrationManifest actual) {
        if (!expected.equals(actual.hash())) throw new SupabaseMigrationException("MIGRATION_MANIFEST_CHANGED");
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
        byte[] bytes = files.readBytes(projectId, MIGRATIONS, name, MAX_MIGRATION_BYTES);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new SupabaseMigrationException("MIGRATION_INVALID_UTF8");
        }
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
