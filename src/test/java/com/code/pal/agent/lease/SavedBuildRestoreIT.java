package com.code.pal.agent.lease;

import com.code.pal.agent.change.PromotionJournal;
import com.code.pal.agent.change.RestoreJournal;
import com.code.pal.agent.change.RestoreRecoveryService;
import com.code.pal.agent.change.SavedBuildManifest;
import com.code.pal.agent.change.SavedBuildRestoreService;
import com.code.pal.agent.change.SavedBuildStore;
import com.code.pal.agent.preview.SiteBuildService;
import com.code.pal.agent.preview.PublishedSiteService;
import com.code.pal.agent.workspace.FencedSandboxPort;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.agent.workspace.WorkspaceStorage;
import com.code.pal.project.capability.BuildDecisions;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SavedBuildRestoreIT {
    private AnnotationConfigApplicationContext app;
    private JdbcTemplate jdbc;
    private ProjectFiles files;
    private ProjectLeaseService leases;
    private RestoreJournal journal;
    private SavedBuildStore store;
    private SavedBuildRestoreService restores;

    @BeforeAll
    void open() {
        app = CodexJpaFixture.open(Services.class);
        jdbc = app.getBean(JdbcTemplate.class);
        files = app.getBean(ProjectFiles.class);
        leases = app.getBean(ProjectLeaseService.class);
        journal = app.getBean(RestoreJournal.class);
        store = app.getBean(SavedBuildStore.class);
        restores = app.getBean(SavedBuildRestoreService.class);
    }

    @AfterAll
    void close() {
        if (app != null) {
            app.close();
        }
    }

    @Test
    void earlierAfterVersionsCanBeRestoredBackAndForwardWithoutRewritingRunHistory() {
        UUID project = project();
        String release = UUID.randomUUID().toString();
        String publishedRoot = ".codepal/published/" + release;
        files.writeText(project, ".codepal/published", "LIVE", release);
        files.writeText(project, publishedRoot, "index.html", "published bytes");
        files.writeText(project, publishedRoot, "RELEASE", "STATIC_V1");
        var published = new PublishedSiteService(mock(SiteBuildService.class), files, app.getBean(FencedSandboxPort.class));
        assertEquals(publishedRoot, published.publishedSite(project));
        UUID first = save(project, "first");
        UUID second = save(project, "second");
        var review = restores.review(7L, project, first);
        UUID id = UUID.randomUUID();
        assertEquals("RESTORED", restores.restore(7L, project, first, id, review.currentDigest()).state());
        assertEquals("first", files.readText(project, "index.html"));
        assertEquals(first, restores.history(7L, project).currentRunId());
        var next = restores.review(7L, project, second);
        assertEquals("RESTORED", restores.restore(7L, project, second, UUID.randomUUID(), next.currentDigest()).state());
        assertEquals("second", files.readText(project, "index.html"));
        assertEquals(second, restores.history(7L, project).currentRunId());
        assertEquals(2, jdbc.queryForObject("select count(*) from agent_runs where project_id=? and code_saved_at is not null", Integer.class, project));
        assertEquals("RESTORED", restores.restore(7L, project, first, id, review.currentDigest()).state());
        assertEquals("second", files.readText(project, "index.html"), "Idempotent replay must not restore again");
        assertEquals(release, files.readText(project, ".codepal/published", "LIVE"));
        assertEquals("published bytes", files.readText(project, publishedRoot, "index.html"));
        assertEquals(publishedRoot, published.publishedSite(project));
    }

    @Test
    void aPendingRestoreFencesNewWorkersAndAReopenedJournalRecoversMixedFiles() {
        UUID project = project();
        files.writeText(project, "b.txt", "first b");
        UUID first = save(project, "first");
        files.writeText(project, "b.txt", "second b");
        save(project, "second");
        UUID id = interrupt(project, first, false);
        assertNull(leases.claim(project, UUID.randomUUID(), UUID.randomUUID()));
        assertThrows(RuntimeException.class, () -> files.writeText(project, "blocked.txt", "no"));
        recover();
        assertEquals("ROLLED_BACK", journal.readState(id));
        assertEquals("second", files.readText(project, "index.html"));
        assertEquals("second b", files.readText(project, "b.txt"));
        assertFalse(leases.hasPendingRestore(project));
        assertNotNull(restores.review(7L, project, first));
    }

    @Test
    void aRestartAfterAllWritesRecognizesTheTargetAndAdvancesTheCodeHead() {
        UUID project = project();
        UUID first = save(project, "first");
        UUID second = save(project, "second");
        UUID id = interrupt(project, first, true);
        recover();
        recover();
        assertEquals("RESTORED", journal.readState(id));
        assertEquals("first", files.readText(project, "index.html"));
        assertNotNull(restores.review(7L, project, second));
    }

    @Test
    void queuedRunsAndLiveWorkerLeasesRefuseTheRealRestoreBoundary() {
        UUID project = project();
        UUID first = save(project, "first");
        UUID worker = UUID.randomUUID();
        insertRun(project, worker, "QUEUED");
        assertThrows(RuntimeException.class, () -> restores.review(7L, project, first));
        jdbc.update("update agent_runs set status='DONE' where id=?", worker);
        var fence = leases.claim(project, worker, UUID.randomUUID());
        assertNotNull(fence);
        try {
            assertThrows(RuntimeException.class, () -> restores.review(7L, project, first));
        } finally {
            leases.release(fence);
        }
    }

    @Test
    void auditAndManifestProofsAreImmutableAndScopedToTheirProject() {
        UUID project = project();
        UUID first = save(project, "first");
        save(project, "second");
        var review = restores.review(7L, project, first);
        UUID id = UUID.randomUUID();
        restores.restore(7L, project, first, id, review.currentDigest());
        assertEquals(7L, journal.read(id).actorId());
        assertThrows(RuntimeException.class, () -> jdbc.update("update agent_code_restores set actor_id=8 where id=?", id));
        assertThrows(RuntimeException.class, () -> jdbc.update("update agent_code_restores set state='APPLYING' where id=?", id));
        assertThrows(RuntimeException.class, () -> jdbc.update("delete from agent_code_restores where id=?", id));
        assertThrows(RuntimeException.class, () -> jdbc.update("update agent_saved_builds set manifest_json='{}' where run_id=?", first));
        assertThrows(RuntimeException.class, () -> restores.operation(7L, project(), id));
    }

    @Test
    void onlyTenRecentSavedVersionsAreOfferedAndOlderAfterBodiesAreRetired() {
        UUID project = project();
        UUID oldest = save(project, "0");
        for (int index = 1; index <= 11; index++) {
            save(project, Integer.toString(index));
        }
        assertEquals(10, restores.list(7L, project).size());
        assertNull(store.find(project, oldest));
        assertFalse(files.exists(project, SavedBuildStore.root(oldest), "index.html"));
        assertEquals(12, jdbc.queryForObject("select count(*) from agent_saved_builds where project_id=?", Integer.class, project));
    }

    @Test
    void legacyBeforeArchivesAndUnfinalizedSnapshotsNeverBecomeAvailableVersions() {
        UUID project = project();
        UUID legacy = UUID.randomUUID();
        insertRun(project, legacy, "DONE");
        jdbc.update("update agent_runs set code_saved_at=clock_timestamp(),snapshot_ref=? where id=?", "archive:" + legacy, legacy);
        assertTrue(store.list(project).isEmpty());
        assertNull(store.current(project));
        assertThrows(RuntimeException.class, () -> restores.review(7L, project, legacy));
        UUID unfinalized = UUID.randomUUID();
        insertRun(project, unfinalized, "RUNNING");
        files.writeText(project, "index.html", "not finalized");
        var fence = leases.claim(project, unfinalized, UUID.randomUUID());
        try (var ignored = RunFence.bind(fence)) {
            leases.guarded(project, () -> store.capture(unfinalized, project));
        } finally {
            leases.release(fence);
        }
        assertTrue(store.list(project).isEmpty());
    }

    @Test
    void aPreparingSavedRestoreIsAbandonedWithoutTouchingCurrentCode() {
        UUID project = project();
        UUID first = save(project, "first");
        save(project, "second");
        UUID id = UUID.randomUUID();
        var point = store.find(project, first);
        String current = files.version(project, ".", "index.html");
        var proof = List.of(new PromotionJournal.FileState("index.html", point.manifest().files().getFirst().version(), current));
        leases.manualRestore(project, () -> journal.prepareSaved(id, first, project, 7L, store.current(project).digest(), proof));
        recover();
        assertEquals("ROLLED_BACK", journal.readState(id));
        assertEquals("second", files.readText(project, "index.html"));
    }

    private UUID interrupt(UUID project, UUID target, boolean all) {
        var old = store.find(project, target);
        var current = SavedBuildManifest.scan(files, project, ".");
        var proof = new ArrayList<PromotionJournal.FileState>();
        for (var file : current.files()) {
            String desired = old.manifest().files().stream().filter(entry -> entry.path().equals(file.path()))
                    .findFirst().orElseThrow().version();
            proof.add(new PromotionJournal.FileState(file.path(), desired, file.version()));
        }
        UUID id = UUID.randomUUID();
        assertThrows(SimulatedCrash.class, () -> leases.manualRestore(project, () -> {
            journal.prepareSaved(id, target, project, 7L, current.digest(), proof);
            for (var file : proof) {
                files.copyFile(project, ".", file.path(), ".codepal/restore/" + id, file.path());
            }
            journal.state(id, "APPLYING");
            for (var file : proof) {
                files.copyFile(project, old.sourceRoot(), file.path(), ".", file.path());
                if (!all) {
                    break;
                }
            }
            throw new SimulatedCrash();
        }));
        return id;
    }

    private void recover() {
        var reopened = new RestoreJournal(jdbc, app.getBean(PlatformTransactionManager.class));
        new RestoreRecoveryService(leases, reopened, app.getBean(PromotionJournal.class), files,
                app.getBean(FencedSandboxPort.class), mock(SiteBuildService.class))
                .recoverPending(System.nanoTime() + 10_000_000_000L);
    }

    private UUID project() {
        UUID project = UUID.randomUUID();
        jdbc.update("insert into projects(id,workspace_id,owner_id,name,type,is_public,deployment_status,created_at,updated_at,last_activity_at) "
                        + "values (?,?,7,'Saved version fixture','BUILD',false,'NONE',now(),now(),now())", project, UUID.randomUUID());
        app.getBean(WorkspaceStorage.class).ensure(project);
        return project;
    }

    private UUID save(UUID project, String body) {
        files.writeText(project, "index.html", body);
        UUID run = UUID.randomUUID();
        insertRun(project, run, "RUNNING");
        var fence = leases.claim(project, run, UUID.randomUUID());
        assertNotNull(fence);
        try (var ignored = RunFence.bind(fence)) {
            leases.guarded(project, () -> {
                store.capture(run, project);
                var proof = SavedBuildManifest.scan(files, project, ".").files().stream()
                        .map(file -> new PromotionJournal.FileState(file.path(), "absent", file.version())).toList();
                jdbc.update("insert into agent_promotions(run_id,project_id,lease_epoch,revision,files_json,state,saved_at) "
                                + "values (?,?,1,1,?,'SAVED',clock_timestamp())", run, project,
                        app.getBean(ObjectMapper.class).writeValueAsString(proof));
                jdbc.update("update agent_runs set status='DONE',code_saved_at=clock_timestamp() where id=?", run);
                store.retireOldBodies(project);
            });
        } finally {
            leases.release(fence);
        }
        return run;
    }

    private void insertRun(UUID project, UUID run, String status) {
        jdbc.update("insert into agent_runs(id,project_id,message_id,user_id,mode,status,prompt_tokens,completion_tokens,created_at,updated_at) "
                + "values (?,?,?,7,'BUILD',?,0,0,now(),now())", run, project, run.toString(), status);
    }

    private static final class SimulatedCrash extends Error {}

    @Configuration
    @Import({ProjectLeaseIT.Services.class, SavedBuildStore.class, SavedBuildRestoreService.class, RestoreJournal.class})
    static class Services {
        @Bean
        BuildDecisions decisions() {
            var result = mock(BuildDecisions.class);
            when(result.state(any())).thenReturn(new BuildDecisions.State(
                    new BuildDecisions.Choice(1, BuildDecisions.Storage.BROWSER, BuildDecisions.Auth.NONE, null),
                    new BuildDecisions.Available(false, null), null));
            return result;
        }
    }
}
