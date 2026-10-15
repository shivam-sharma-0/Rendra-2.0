package com.code.pal.agent.lease;

import com.code.pal.agent.change.PromotionJournal;
import com.code.pal.agent.change.RestoreJournal;
import com.code.pal.agent.change.RestoreRecoveryService;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.SandboxPort;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in disposable SQL fixture only; never uses the dev DB or starts an application server. */
@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RestoreRecoveryBackoffIT {
    private AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private RestoreJournal journal;

    @BeforeAll
    void open() {
        app = CodexJpaFixture.open(ProjectLeaseIT.Services.class);
        jdbc = app.getBean(JdbcTemplate.class);
        journal = new RestoreJournal(jdbc, app.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    void close() {
        if (app != null) app.close();
    }

    @Test
    void recentQuarantineIsNotRehashedUntilBackoffExpires() {
        RestoreJournal.Entry entry = createRestore("RECOVERY_REQUIRED");
        var files = mock(ProjectFiles.class);
        var hashes = new AtomicInteger();
        when(files.version(eq(entry.projectId()), anyString(), eq("a.txt"))).thenAnswer(call -> {
            hashes.incrementAndGet();
            return "sha256:" + "c".repeat(64); // Neither before nor after: deliberately unproven.
        });
        var promotions = mock(PromotionJournal.class);
        when(promotions.read(entry.runId())).thenReturn(new PromotionJournal.Entry(entry.runId(), entry.projectId(),
                null, 1, 1, List.of(new PromotionJournal.FileState("a.txt", "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64))), "SAVED", Instant.parse("2026-01-01T00:00:00Z")));
        var sandbox = mock(SandboxPort.class);
        when(sandbox.reconcilePendingOperations(entry.projectId())).thenReturn(true);
        var recovery = new RestoreRecoveryService(app.getBean(ProjectLeaseService.class), journal, promotions,
                files, sandbox, mock(SiteBuildService.class));
        Instant anchor = databaseNow();
        jdbc.update("update agent_code_restores set updated_at=? where id=?",
                Timestamp.from(anchor.minus(Duration.ofDays(1))), entry.id());
        try {
            recovery.recoverPending(Long.MAX_VALUE);
            int firstPassHashes = hashes.get();
            assertTrue(firstPassHashes > 0, "An old quarantine must receive its initial reconciliation");
            assertEquals("RECOVERY_REQUIRED", journal.readState(entry.id()));

            var clock = new QuarantineClock(databaseNow());
            clock.applyAge(jdbc, entry.id());
            recovery.recoverPending(Long.MAX_VALUE);
            assertEquals(firstPassHashes, hashes.get(), "The next sweep must not re-hash a recent quarantine");

            clock.advance(Duration.ofDays(1));
            clock.applyAge(jdbc, entry.id());
            recovery.recoverPending(Long.MAX_VALUE);
            assertTrue(hashes.get() > firstPassHashes, "Backoff expiry must make the row retryable again");
        } finally {
            journal.state(entry.id(), "ROLLED_BACK");
        }
    }

    @Test
    void recentQuarantinesDoNotHideANewerRestoreBehindTheHundredRowLimit() {
        var entries = new ArrayList<RestoreJournal.Entry>();
        try {
            Instant anchor = databaseNow();
            for (int index = 0; index < 100; index++) {
                RestoreJournal.Entry entry = createRestore("RECOVERY_REQUIRED");
                entries.add(entry);
                jdbc.update("update agent_code_restores set created_at=?,updated_at=? where id=?",
                        Timestamp.from(anchor.minus(Duration.ofDays(2))), Timestamp.from(anchor), entry.id());
            }
            RestoreJournal.Entry newer = createRestore("PREPARING");
            entries.add(newer);

            assertTrue(journal.pending().stream().anyMatch(entry -> entry.id().equals(newer.id())),
                    "Backoff exclusion must happen before LIMIT 100, so a newer restore is not starved");
        } finally {
            for (RestoreJournal.Entry entry : entries) journal.state(entry.id(), "ROLLED_BACK");
        }
    }

    private RestoreJournal.Entry createRestore(String state) {
        UUID project = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) "
                + "values (?,?,7,'Backoff fixture','BUILD',false,'NONE',now(),now(),now())", project, UUID.randomUUID());
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) "
                + "values (?,?,?,7,'BUILD','DONE',0,0,now(),now())", run, project, run.toString());
        String backup = ".codepal/restore/" + id;
        journal.prepare(id, run, project, backup);
        if (!state.equals("PREPARING")) journal.state(id, state);
        return new RestoreJournal.Entry(id, run, project, backup, state);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    /** Virtual age is translated into persisted timestamps, retaining the real journal's DB-clock query. */
    private static final class QuarantineClock extends Clock {
        private final Instant origin;
        private Instant current;

        private QuarantineClock(Instant origin) {
            this.origin = origin;
            this.current = origin;
        }

        private void advance(Duration elapsed) {
            current = current.plus(elapsed);
        }

        private void applyAge(JdbcTemplate jdbc, UUID id) {
            Duration age = Duration.between(origin, instant());
            jdbc.update("update agent_code_restores set updated_at=? where id=?", Timestamp.from(origin.minus(age)), id);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
