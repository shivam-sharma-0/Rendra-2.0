package com.code.pal.agent;

import com.code.pal.agent.dispatch.*;
import com.code.pal.agent.domain.AgentRun;
import com.code.pal.agent.queue.AgentJobQueue;
import com.code.pal.agent.repository.AgentRunRepository;
import com.code.pal.agent.workspace.ProjectFiles;
import com.code.pal.message.MessageSequence;
import com.code.pal.message.domain.Message;
import com.code.pal.message.dto.CreateMessageRequest;
import com.code.pal.message.exception.MessageConflictException;
import com.code.pal.message.repository.MessageRepository;
import com.code.pal.message.service.MessageService;
import com.code.pal.message.service.impl.MessageServiceImpl;
import com.code.pal.project.domain.Project;
import com.code.pal.project.domain.ProjectType;
import com.code.pal.project.event.AssistantTurnRequested;
import com.code.pal.project.service.ProjectService;
import com.code.pal.realtime.RealtimeService;
import com.code.pal.shared.usage.AgentTurnAdmission;
import com.code.pal.testsupport.CodexJpaFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "codepal.disposable.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AtomicAgentAcceptanceIT {
    private org.springframework.context.annotation.AnnotationConfigApplicationContext context;
    private UUID projectId;
    private MessageService messages;
    private AgentRunRepository runs;
    private AgentDispatchRepository outbox;
    private AgentJobQueue queue;
    private JdbcTemplate jdbc;

    @BeforeAll void initialize() { context = CodexJpaFixture.open(Services.class); }
    @AfterAll void close() { if (context != null) context.close(); }
    @BeforeEach void scenario() {
        projectId = UUID.randomUUID();
        messages = context.getBean(MessageService.class);
        runs = context.getBean(AgentRunRepository.class);
        outbox = context.getBean(AgentDispatchRepository.class);
        queue = context.getBean(AgentJobQueue.class);
        jdbc = context.getBean(JdbcTemplate.class);
        reset(queue);
        when(context.getBean(ProjectService.class).requireCanEdit(eq(7L), eq(projectId))).thenReturn(
                Project.builder().id(projectId).ownerId(7L).workspaceId(UUID.randomUUID()).type(ProjectType.CHAT).build());
    }

    private CreateMessageRequest request(String suffix) {
        return new CreateMessageRequest("u_" + suffix, "Explain existing code", ProjectType.CHAT, "a_" + suffix);
    }
    @Test void explicitReadOnlyScopePersistsEffectiveModeAndOriginalModeForIdempotency() {
        when(context.getBean(ProjectService.class).requireCanEdit(eq(7L),eq(projectId))).thenReturn(
                Project.builder().id(projectId).ownerId(7L).workspaceId(UUID.randomUUID()).type(ProjectType.BUILD)
                        .lifecycleStage(com.code.pal.project.domain.ProjectStage.REVIEW).build());
        var request=new CreateMessageRequest("u_readonly","Dont change anything, explain this API",ProjectType.BUILD,"a_readonly");
        messages.post(7L,projectId,request);messages.post(7L,projectId,request);
        var run=runs.findByProjectIdAndRequestMessageId(projectId,"u_readonly").orElseThrow();
        assertEquals(ProjectType.CHAT,run.getMode());
        var saved=context.getBean(MessageRepository.class).findByIdAndProjectId("u_readonly",projectId).orElseThrow();
        assertEquals(ProjectType.CHAT,saved.getMode());assertEquals(ProjectType.BUILD,saved.getRequestedMode());
        assertEquals(1,runs.countByProjectId(projectId));
    }
    private AgentRun accepted(String suffix) {
        messages.post(7L, projectId, request(suffix));
        return runs.findByProjectIdAndRequestMessageId(projectId, "u_" + suffix).orElseThrow();
    }

    @Test void concurrentStopAndSaveHaveExactlyOneDurableWinner() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            for(int round=0;round<12;round++) {
                var run=accepted(UUID.randomUUID().toString().substring(0,12));
                run.setStatus(com.code.pal.agent.domain.RunStatus.RUNNING);runs.save(run);
                var start=new java.util.concurrent.CountDownLatch(1);
                var stop=pool.submit(()->{start.await();return runs.recordCancellation(run.getId(),projectId,8L,Instant.now());});
                var save=pool.submit(()->{start.await();return runs.beginCommit(run.getId(),projectId,Instant.now());});
                start.countDown();int cancelled=stop.get(5,java.util.concurrent.TimeUnit.SECONDS),committed=save.get(5,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1,cancelled+committed);
                var durable=runs.findById(run.getId()).orElseThrow();
                if(cancelled==1) {
                    assertNotNull(durable.getCancelRequestedAt());assertNull(durable.getCommitStartedAt());
                    assertEquals(0,runs.beginCommit(run.getId(),projectId,Instant.now()));
                } else {
                    assertEquals(com.code.pal.agent.domain.RunStatus.COMMITTING,durable.getStatus());
                    assertNull(durable.getCancelRequestedAt());assertNotNull(durable.getCommitStartedAt());
                    assertEquals(0,runs.recordCancellation(run.getId(),projectId,8L,Instant.now()));
                }
            }
        }
    }
    @Test void staleSavesCannotEraseCodeSavedAndPreviewTruth() {
        var run=accepted(UUID.randomUUID().toString().substring(0,12));run.setStatus(com.code.pal.agent.domain.RunStatus.RUNNING);runs.save(run);
        assertEquals(1,runs.beginCommit(run.getId(),projectId,Instant.now()));
        var stale=runs.findById(run.getId()).orElseThrow();
        assertEquals(1,runs.recordCodeSaved(run.getId(),Instant.now()));assertEquals(1,runs.recordPreviewStatus(run.getId(),"FAILED"));
        stale.setError("Stale display write");runs.save(stale);
        var durable=runs.findById(run.getId()).orElseThrow();assertNotNull(durable.getCodeSavedAt());assertEquals("FAILED",durable.getPreviewStatus());
    }

    @Test void acceptsMessagesRunAndOutboxTogetherAndRetriesWithoutANewRun() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        AgentRun run = accepted(key);
        assertNotNull(run.getRequestFingerprint());
        assertEquals(2, messages.post(7L, projectId, request(key)).size());
        assertEquals(1, runs.countByProjectId(projectId));
        assertNotNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        verify(queue, times(1)).enqueue(run.getId());
        context.getBean(AgentDispatchService.class).dispatch(run.getId());
        verify(queue, times(1)).enqueue(run.getId());
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest("u_" + key, "Different request", ProjectType.CHAT, "a_" + key)));
    }

    @Test void queueOutageLeavesReplayableCommittedIntent() {
        doThrow(new IllegalStateException("Scripted queue outage")).when(queue).enqueue(any());
        String key = UUID.randomUUID().toString().substring(0, 12);
        var run = accepted(key);
        assertEquals(2, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        reset(queue);
        context.getBean(AgentDispatchPoller.class).poll();
        assertNotNull(outbox.findById(run.getId()).orElseThrow().getDispatchedAt());
        verify(queue).enqueue(run.getId());
    }

    @Test void failureBeforeCommitLeavesNoAcceptedMessageRunOrDelivery() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            messages.post(7L, projectId, request(key));
            // A second malformed delivery event fails BEFORE_COMMIT after the valid intent is created.
            context.publishEvent(new AssistantTurnRequested(projectId, 7L, ProjectType.CHAT, "missing"));
        }));
        assertEquals(0, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertEquals(0, runs.countByProjectId(projectId));
        verifyNoInteractions(queue);
    }

    @Test void originalHistoryIsBoundedInPostgresAndStaleSavesPreserveCancelAudit() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var run = accepted(key);
        var repo = context.getBean(MessageRepository.class);
        var origin = repo.findById(run.getRequestMessageId()).orElseThrow();
        Message later = Message.user(projectId, "later_" + key, "Do something else", ProjectType.CHAT, 8L);
        // SQL enforces an exact same-clock tie; later seq must be excluded before pagination.
        jdbc.update("insert into messages(id,project_id,author_id,role,status,mode,content,created_at,updated_at,seq,attachments) values (?,?,8,'USER','COMPLETE','CHAT',?,?,?, ?,?)",
                later.getId(), projectId, later.getContent(), java.sql.Timestamp.from(origin.getCreatedAt()),
                java.sql.Timestamp.from(origin.getCreatedAt()), origin.getSeq() + 10, "uploads/b.png");
        var history = RunRequestHistory.load(repo, run, 1);
        assertEquals(origin.getId(), history.messages().getLast().getId());
        assertEquals(1, history.messages().size());
        assertEquals(1, runs.recordCancellation(run.getId(), projectId, 8L, Instant.now()));
        assertEquals(0, runs.recordCancellation(run.getId(), projectId, 9L, Instant.now()));
        run.setError("Stale runner save");
        runs.saveAndFlush(run);
        assertEquals(8L, runs.findById(run.getId()).orElseThrow().getCancelRequestedBy());
    }

    @Test void concurrentOutboxReplayEnqueuesOnce() throws Exception {
        doThrow(new IllegalStateException("Scripted queue outage")).when(queue).enqueue(any());
        var run = accepted(UUID.randomUUID().toString().substring(0, 12));
        reset(queue);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> replay = () -> {
                assertTrue(start.await(3, java.util.concurrent.TimeUnit.SECONDS));
                context.getBean(AgentDispatchService.class).dispatch(run.getId());
                return null;
            };
            var first = pool.submit(replay);
            var second = pool.submit(replay);
            start.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(queue, times(1)).enqueue(run.getId());
    }

    @Test void modifiedAcceptedPromptFailsInsteadOfExecutingTheNewText() {
        var run = accepted(UUID.randomUUID().toString().substring(0, 12));
        jdbc.update("update messages set content = 'modified after acceptance' where id = ?", run.getRequestMessageId());
        assertThrows(IllegalStateException.class, () -> RunRequestHistory.load(context.getBean(MessageRepository.class), run, 100));
    }

    @Test void additiveMigrationPreservesLegacyDuplicateRuns() {
        assertEquals(2, jdbc.queryForObject("select count(*) from agent_runs where message_id = 'legacy_duplicate'", Integer.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from agent_runs where message_id = 'legacy_duplicate' and request_message_id is not null", Integer.class));
    }

    @Test void concurrentDuplicateSubmitHasOneDurableRunAndCanRetry() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 12);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> submit = () -> {
                assertTrue(start.await(3, java.util.concurrent.TimeUnit.SECONDS));
                try { messages.post(7L, projectId, request(key)); return true; }
                catch (org.springframework.dao.DataIntegrityViolationException concurrentInsert) { return false; }
            };
            var first = pool.submit(submit);
            var second = pool.submit(submit);
            start.countDown();
            boolean a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            boolean b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(a || b);
        }
        assertEquals(1, runs.countByProjectId(projectId));
        assertEquals(2, context.getBean(MessageRepository.class).countByProjectId(projectId));
        assertEquals(2, messages.post(7L, projectId, request(key)).size());
        var run = runs.findByProjectIdAndRequestMessageId(projectId, "u_" + key).orElseThrow();
        verify(queue, times(1)).enqueue(run.getId());
    }

    @Test void retriesKeepCanonicalImagesAndRejectDifferentModeOrAssistant() {
        String key = UUID.randomUUID().toString().substring(0, 12);
        when(context.getBean(ProjectFiles.class).regularFile(projectId, ".", "uploads/a.png")).thenReturn(true);
        var request = new CreateMessageRequest("u_" + key, "Read image", ProjectType.CHAT, "a_" + key,
                java.util.List.of("uploads/a.png", "uploads/a.png"));
        messages.post(7L, projectId, request);
        when(context.getBean(ProjectFiles.class).regularFile(projectId, ".", "uploads/a.png")).thenReturn(false);
        assertEquals(2, messages.post(7L, projectId, request).size());
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest(request.id(), request.message(), ProjectType.BUILD, request.aiMessageId(), request.attachments())));
        assertThrows(MessageConflictException.class, () -> messages.post(7L, projectId,
                new CreateMessageRequest(request.id(), request.message(), ProjectType.CHAT, "different", request.attachments())));
    }

    @Configuration
    static class Services {
        @Bean ProjectService projects() { return mock(ProjectService.class); }
        @Bean RealtimeService realtime() { return mock(RealtimeService.class); }
        @Bean AgentJobQueue queue() { return mock(AgentJobQueue.class); }
        @Bean AgentEvents events() { return mock(AgentEvents.class); }
        @Bean AgentTurnAdmission admission() { return mock(AgentTurnAdmission.class); }
        @Bean ProjectFiles files() { return mock(ProjectFiles.class); }
        @Bean MessageSequence sequence() { return new MessageSequence(); }
        @Bean MessageServiceImpl messageService(MessageRepository m, ProjectService p, RealtimeService r,
                                               ApplicationEventPublisher e, MessageSequence s, AgentTurnAdmission a, ProjectFiles f) {
            return new MessageServiceImpl(m, p, r, e, s, a, f);
        }
        @Bean AgentRunCreator creator(AgentRunRepository r, MessageRepository m, AgentDispatchRepository o) {
            return new AgentRunCreator(r, m, o);
        }
        @Bean AgentDispatchService dispatcher(AgentDispatchRepository o, AgentRunRepository r, AgentJobQueue q, AgentEvents e) {
            return new AgentDispatchService(o, r, q, e);
        }
        @Bean AgentTriggerListener listener(AgentRunCreator c, AgentRunRepository r, AgentDispatchService d) {
            return new AgentTriggerListener(c, r, d);
        }
        @Bean AgentDispatchPoller poller(AgentDispatchRepository o, AgentDispatchService d) { return new AgentDispatchPoller(o, d); }
    }
}
