package com.code.pal.project.supabase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All JDBC calls are mocked; these tests need no running database or application. */
class SelfHostedSupabaseIsolationTest {
    private static final String URL = "https://operator.invalid";
    private static final String DB = "jdbc:postgresql://operator.invalid/private_fixture?password=url-fixture-secret";
    private static final String USER = "fixture-user-never-log";
    private static final String PASSWORD = "fixture-password-never-log";
    private static final String SQL = "select 'fixture-sql-never-log';";
    private static final String FLAG = "codepal.supabase.self-hosted.operator-sql-enabled";

    @ParameterizedTest
    @ValueSource(strings = {"postgres", "renamed_migration_owner"})
    void sharedAdminAndRenamedRolesCannotExecuteTenantSql(String role) {
        try (var jdbc = mockStatic(DriverManager.class)) {
            var instance = new SelfHostedSupabase(URL, DB, role, PASSWORD);
            assertFalse(instance.migrationsAvailable());
            var failure = assertThrows(SelfHostedSqlDisabledException.class, () -> instance.runSql(SQL));
            assertEquals("SELF_HOSTED_SQL_DISABLED", failure.code());
            assertEquals(HttpStatus.FORBIDDEN, failure.status());
            assertNoCredentials(failure);
            jdbc.verifyNoInteractions();
        }
    }

    @Test
    void aMissingOperatorPropertyDefaultsToRefusalEvenWithAConfiguredDatabase() {
        try (var jdbc = mockStatic(DriverManager.class); var context = context()) {
            var instance = context.getBean(SelfHostedSupabase.class);
            assertTrue(instance.matches(URL));
            assertFalse(instance.operatorSqlEnabled());
            assertFalse(instance.migrationsAvailable());
            assertThrows(SelfHostedSqlDisabledException.class, () -> instance.runSql(SQL));
            jdbc.verifyNoInteractions();
        }
    }

    @Test
    void anExplicitPropertyOffRefusesBeforeDatabaseAccess() {
        try (var jdbc = mockStatic(DriverManager.class); var context = context(FLAG + "=false")) {
            var instance = context.getBean(SelfHostedSupabase.class);
            assertFalse(instance.operatorSqlEnabled());
            assertThrows(SelfHostedSqlDisabledException.class, instance::requireOperatorSqlEnabled);
            assertNoCredentials(assertThrows(SelfHostedSqlDisabledException.class, () -> instance.runSql(SQL)));
            jdbc.verifyNoInteractions();
        }
    }

    @Test
    void theLegacyAppPrefixCannotAccidentallyEnableOperatorSql() {
        try (var jdbc = mockStatic(DriverManager.class);
                var context = context("app.supabase.self-hosted.operator-sql-enabled=true")) {
            assertThrows(SelfHostedSqlDisabledException.class, () -> context.getBean(SelfHostedSupabase.class).runSql(SQL));
            jdbc.verifyNoInteractions();
        }
    }

    @Test
    void aPropertyOnRequiresOperatorOptInAndKeepsTransactionAndSchemaReload() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        try (var jdbc = mockStatic(DriverManager.class); var context = context(FLAG + "=true")) {
            jdbc.when(() -> DriverManager.getConnection(DB, USER, PASSWORD)).thenReturn(connection);
            var instance = context.getBean(SelfHostedSupabase.class);
            assertTrue(instance.operatorSqlEnabled());
            assertTrue(instance.migrationsAvailable());
            instance.runSql(SQL);
            var order = inOrder(connection, statement);
            order.verify(connection).setAutoCommit(false);
            order.verify(connection).createStatement();
            order.verify(statement).setQueryTimeout(90);
            order.verify(statement).execute(SQL);
            order.verify(statement).execute("NOTIFY pgrst, 'reload schema'");
            order.verify(connection).commit();
            verify(statement).close();
            verify(connection).close();
            verify(connection, never()).rollback();
        }
    }

    @Test
    void operatorOptInEmitsAStartupWarningWithoutCredentials() {
        Logger logger = (Logger) LoggerFactory.getLogger(SelfHostedSupabase.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try (var ignored = context(FLAG + "=true")) {
            assertTrue(events.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                    && event.getFormattedMessage().contains("supabase_operator_sql_enabled")
                    && event.getFormattedMessage().contains("single-tenant development")));
            events.list.forEach(event -> {
                assertSafeText(event.getFormattedMessage());
                assertNull(event.getThrowableProxy());
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void enabledConnectionErrorsDoNotExposeJdbcCredentialsOrDriverMessages() {
        // SQLException's constructor consults DriverManager, so it is built before the static mock starts.
        var refused = new SQLException(DB + " " + USER + " " + PASSWORD, "08001");
        try (var jdbc = mockStatic(DriverManager.class)) {
            jdbc.when(() -> DriverManager.getConnection(DB, USER, PASSWORD))
                    .thenThrow(refused);
            var instance = new SelfHostedSupabase(URL, DB, USER, PASSWORD, true);
            var failure = assertThrows(SupabaseException.class, () -> instance.runSql(SQL));
            assertTrue(failure.getMessage().contains("SQLSTATE 08001"));
            assertNoCredentials(failure);
        }
    }

    @Test
    void anOperatorMigrationRollsBackWithoutLeakingSqlOrDatabaseDetails() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(SQL)).thenThrow(new SQLException(SQL + " " + DB + " " + PASSWORD, "42P01"));
        try (var jdbc = mockStatic(DriverManager.class)) {
            jdbc.when(() -> DriverManager.getConnection(DB, USER, PASSWORD)).thenReturn(connection);
            var instance = new SelfHostedSupabase(URL, DB, USER, PASSWORD, true);
            var failure = assertThrows(SupabaseException.class, () -> instance.runSql(SQL));
            assertTrue(failure.getMessage().contains("SQLSTATE 42P01"));
            assertNoCredentials(failure);
            verify(connection).rollback();
            verify(connection, never()).commit();
            verify(statement, never()).execute("NOTIFY pgrst, 'reload schema'");
        }
    }

    @Test
    void malformedSqlStatesCannotLeakDriverTextThroughTheSanitizedError() {
        var refused = new SQLException("Connection failed", PASSWORD);
        try (var jdbc = mockStatic(DriverManager.class)) {
            jdbc.when(() -> DriverManager.getConnection(DB, USER, PASSWORD))
                    .thenThrow(refused);
            var instance = new SelfHostedSupabase(URL, DB, USER, PASSWORD, true);
            var failure = assertThrows(SupabaseException.class, () -> instance.runSql(SQL));
            assertTrue(failure.getMessage().contains("SQLSTATE unknown"));
            assertNoCredentials(failure);
        }
    }

    private static AnnotationConfigApplicationContext context(String... properties) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                "app.supabase.self-hosted.url=" + URL, "app.supabase.self-hosted.db-url=" + DB,
                "app.supabase.self-hosted.db-user=" + USER, "app.supabase.self-hosted.db-password=" + PASSWORD);
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, properties);
        context.register(SelfHostedSupabase.class);
        context.refresh();
        return context;
    }

    private static void assertNoCredentials(Throwable failure) {
        assertSafeText(failure.toString());
        assertNull(failure.getCause(), "Raw driver exceptions must not reach error handlers");
        assertEquals(0, failure.getSuppressed().length);
    }

    private static void assertSafeText(String text) {
        for (String secret : new String[] {URL, DB, USER, PASSWORD, SQL, "url-fixture-secret"}) {
            assertFalse(text.contains(secret), "Error/log must not expose fixture credentials or SQL");
        }
    }
}
